package com.example.smoothcensor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主界面：两个按钮，申请两个权限，然后启动服务。
 *
 * 需要的两个权限：
 *   1. 悬浮窗权限 —— 才能在其他应用上层画遮挡
 *   2. 录屏权限   —— 才能拿到屏幕画面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001
        private const val REQ_CAPTURE = 1002
        private const val REQ_NOTIFICATION = 1003
    }

    private lateinit var statusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val scope = CoroutineScope(Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        overlayButton = findViewById(R.id.overlayButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        overlayButton.setOnClickListener { requestOverlayPermission() }
        startButton.setOnClickListener { requestCapturePermission() }
        stopButton.setOnClickListener { stopService() }

        requestNotificationPermissionIfNeeded()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        startStatusLoop()
    }

    override fun onPause() {
        super.onPause()
        statusJob?.cancel()
    }

    // ------------------------------------------------------------ 权限

    /** 悬浮窗权限必须去系统设置里给，没法直接弹窗申请 */
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已经给过了")
            refreshUi()
            return
        }
        startActivityForResult(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            REQ_OVERLAY
        )
    }

    private fun requestCapturePermission() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先给悬浮窗权限")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_OVERLAY -> {
                refreshUi()
                if (Settings.canDrawOverlays(this)) toast("悬浮窗权限已开启")
            }

            REQ_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, CaptureService::class.java).apply {
                        putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    toast("已启动，去别的应用试试")
                    refreshUi()
                } else {
                    toast("你拒绝了录屏权限")
                }
            }
        }
    }

    // ------------------------------------------------------------ 状态

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (true) {
                if (CaptureService.isRunning) {
                    statusText.text = buildString {
                        append("状态：运行中 ✅\n")
                        append("推理耗时：${CaptureService.lastInferenceMs} ms\n")
                        append("检测到：${CaptureService.lastDetectionCount} 个目标\n")
                        append(String.format("处理帧率：%.1f fps", CaptureService.lastFps))
                    }
                }
                delay(500)
            }
        }
    }

    private fun refreshUi() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val running = CaptureService.isRunning

        overlayButton.text = if (hasOverlay) "① 悬浮窗权限 ✅" else "① 开启悬浮窗权限"
        startButton.isEnabled = hasOverlay && !running
        stopButton.isEnabled = running

        statusText.text = when {
            running -> "状态：运行中 ✅"
            !hasOverlay -> "状态：等待悬浮窗权限\n\n先去点上面的按钮授权"
            else -> "状态：已就绪，可以启动"
        }
    }

    // ------------------------------------------------------------ 其它

    private fun stopService() {
        startService(Intent(this, CaptureService::class.java).apply {
            action = "STOP"
        })
        toast("已停止")
        refreshUi()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
