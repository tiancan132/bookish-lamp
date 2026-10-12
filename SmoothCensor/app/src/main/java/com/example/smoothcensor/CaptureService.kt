package com.example.smoothcensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 主服务：录屏 -> AI 检测 -> 画遮挡。
 *
 * 数据流：
 *   MediaProjection 抓帧
 *        -> ImageReader 拿到 Bitmap
 *        -> 丢进 Channel（有界队列，满了就丢旧帧，保证不卡）
 *        -> 独立线程做 AI 推理
 *        -> TemporalSmoother 平滑
 *        -> OverlayView 画出来
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "smoothcensor"
        private const val NOTIF_ID = 42

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 检测频率：每秒最多处理多少帧。太高会烫手，太低会卡顿 */
        const val TARGET_FPS = 10

        /** 帧队列容量，满了丢弃最旧的 */
        private const val FRAME_QUEUE = 2

        @Volatile
        var isRunning = false
            private set

        /** 供界面查询的实时状态 */
        @Volatile
        var lastFps: Float = 0f
        @Volatile
        var lastInferenceMs: Long = 0
        @Volatile
        var lastDetectionCount: Int = 0
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var overlayView: OverlayView
    private var overlayAdded = false

    private val engine by lazy { DetectorEngine(applicationContext) }
    private val smoother = TemporalSmoother()

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var frameChannel: Channel<Bitmap>? = null
    private var detectionJob: Job? = null

    private var screenW = 0
    private var screenH = 0
    private var frameCount = 0
    private var lastFpsTime = 0L

    /** 上次处理的帧，用于复用内存 */
    private var reusableBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "缺少录屏授权数据")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        startPipeline(resultCode, resultData)
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 启动流程

    private fun startPipeline(resultCode: Int, resultData: Intent) {
        if (isRunning) {
            Log.w(TAG, "已经在运行了")
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.e(TAG, "获取 MediaProjection 失败")
            stopSelf()
            return
        }
        projection = proj

        // 注册回调，防止系统回收
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection 被停止")
                stopEverything()
            }
        }, Handler(mainLooper))

        // 屏幕尺寸
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        val density = metrics.densityDpi

        Log.i(TAG, "屏幕尺寸 ${screenW}x$screenH @${density}dpi")

        // 添加遮挡层
        addOverlay()

        // 初始化 AI（放后台，避免阻塞）
        workerThread = HandlerThread("detector").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)

        workerHandler?.post {
            engine.initialize()
            Log.i(TAG, "引擎就绪=${engine.isReady} 后端=${engine.activeProvider}")
        }

        // 创建帧队列
        val channel = Channel<Bitmap>(capacity = FRAME_QUEUE)
        frameChannel = channel

        // 启动检测消费协程
        detectionJob = scope.launch(Dispatchers.Default) {
            for (frame in channel) {
                processFrame(frame)
            }
        }

        // 创建 VirtualDisplay
        imageReader = ImageReader.newInstance(
            screenW, screenH, PixelFormat.RGBA_8888, 3
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                onFrameAvailable(r)
            }, workerHandler)
        }

        virtualDisplay = proj.createVirtualDisplay(
            "SmoothCensorCapture",
            screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, workerHandler,
        )

        isRunning = true
        lastFpsTime = System.currentTimeMillis()
        Log.i(TAG, "✅ 流水线已启动")
    }

    // ---------------------------------------------------------------- 抓帧

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                // 队列满就丢弃这一帧，绝不阻塞。
                // 注意：被丢弃的帧必须回收，否则会内存泄漏。
                val accepted = frameChannel?.trySend(bitmap)
                if (accepted == null || accepted.isFailure) {
                    bitmap.recycle()
                }
            }
        } finally {
            image.close()
        }
    }

    /** 把 ImageReader 的帧转成 Bitmap（去掉行间距） */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenW

        val bmpW = screenW + rowPadding / pixelStride
        val bmp = reusableBitmap?.takeIf { it.width == bmpW && it.height == screenH }
            ?: Bitmap.createBitmap(bmpW, screenH, Bitmap.Config.ARGB_8888)
                .also { reusableBitmap = it }

        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)

        // 行间距不为 0 时，宽出来的部分是垃圾像素，得裁掉。
        // 注意：这里必须"复制"出新图，不能返回子视图 ——
        // 因为子视图和原图共用像素，回收它会连带毁掉复用缓冲区。
        return if (rowPadding == 0) bmp.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
    }

    // ---------------------------------------------------------------- 处理

    private fun processFrame(frame: Bitmap) {
        // 引擎还没好就跳过
        if (!engine.isReady) {
            frame.recycle()
            return
        }

        val t0 = System.currentTimeMillis()
        val detections = engine.detect(frame)
        val inferMs = System.currentTimeMillis() - t0
        frame.recycle()

        // 平滑处理（核心：解决闪烁）
        smoother.update(detections)

        val visible = smoother.visibleDetections
        lastInferenceMs = inferMs
        lastDetectionCount = visible.size

        // 统计帧率
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            lastFps = frameCount * 1000f / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
        }

        // 更新界面
        workerHandler?.post {
            overlayView.updateBoxes(visible)
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun addOverlay() {
        if (overlayAdded) return
        overlayView = OverlayView(this)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(overlayView, OverlayView.layoutParams())
            overlayAdded = true
            Log.i(TAG, "遮挡层已添加")
        } catch (e: Exception) {
            Log.e(TAG, "添加遮挡层失败（悬浮窗权限没给？）: ${e.message}")
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(overlayView)
        } catch (e: Exception) {
            Log.w(TAG, "移除遮挡层失败: ${e.message}")
        }
        overlayAdded = false
    }

    // ---------------------------------------------------------------- 清理

    private fun stopEverything() {
        Log.i(TAG, "正在停止…")
        isRunning = false

        detectionJob?.cancel()
        frameChannel?.close()
        frameChannel = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        scope.cancel()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        engine.close()
        removeOverlay()

        reusableBitmap?.recycle()
        reusableBitmap = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "已停止")
    }

    override fun onDestroy() {
        if (isRunning) stopEverything()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "屏幕遮挡", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "正在检测屏幕内容"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SmoothCensor 运行中")
            .setContentText("正在检测屏幕内容")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
