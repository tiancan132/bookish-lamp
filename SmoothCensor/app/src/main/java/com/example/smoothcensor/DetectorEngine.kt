package com.example.smoothcensor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

/**
 * AI 检测引擎。
 *
 * 和原版软件的区别（针对"闪烁"问题的改进）：
 *  1. 多帧投票：连续 N 帧都检测到才认为有效（写在 TemporalSmoother 里）
 *  2. 每帧独立推理，但结果交给平滑器处理
 *  3. 模型加载失败时自动降级：NNAPI -> XNNPACK -> CPU
 */
class DetectorEngine(
    private val context: Context,
    private val config: DetectorConfig = DetectorConfig(),
) {
    companion object {
        private const val TAG = "DetectorEngine"

        /** 模型类别数（NudeNet 320n 固定 18 类） */
        const val NUM_CLASSES = 18

        /** 模型输出特征数：4 个框坐标 + 1 个置信度 + 18 个类别 = 23 */
        const val OUTPUT_FEATURES = 4 + 1 + NUM_CLASSES

        /** 模型输入是方形，这里用 320（和原版一致，够快） */
        const val INPUT_SIZE = 320

        /** 归一化除数 255 */
        private const val INV_255 = 1f / 255f

        /**
         * 18 个类别名，顺序必须和模型训练时一致。
         * 这些名字是从模型元数据里读出来的。
         */
        val CLASS_NAMES = listOf(
            "FEMALE_GENITALIA_COVERED",
            "FACE_FEMALE",
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED",
            "FEET_EXPOSED",
            "BELLY_COVERED",
            "FEET_COVERED",
            "ARMPITS_COVERED",
            "ARMPITS_EXPOSED",
            "FACE_MALE",
            "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "ANUS_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )

        /**
         * 每个类别的最低置信度。
         *
         * 原版软件里也是这么做的：不同部位用不同门槛，
         * 暴露类宁可错杀也不放过。
         */
        private val MIN_SCORE = mapOf(
            "FEMALE_BREAST_EXPOSED" to 0.40f,
            "FEMALE_GENITALIA_EXPOSED" to 0.40f,
            "MALE_GENITALIA_EXPOSED" to 0.40f,
        )
        private const val DEFAULT_MIN_SCORE = 0.55f

        /** NMS 去重阈值 */
        private const val NMS_IOU = 0.45f
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /** 复用的输入缓冲区，避免每帧分配内存 */
    private var inputBuffer: FloatBuffer? = null

    /** letterbox 用的画布，复用 */
    private var letterboxBitmap: Bitmap? = null

    var activeProvider: String = "none"
        private set

    val isReady: Boolean get() = session != null

    /**
     * 加载模型。按 NNAPI -> XNNPACK -> CPU 顺序尝试。
     * 这样在低端手机上也能跑起来。
     */
    fun initialize() {
        if (session != null) return

        val ortEnv = OrtEnvironment.getEnvironment()
        env = ortEnv

        val candidates = listOf("320n_fp16.onnx", "320n.onnx")

        for (modelFile in candidates) {
            val bytes = try {
                context.assets.open(modelFile).use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "模型 $modelFile 不存在，跳过")
                continue
            }

            Log.i(TAG, "尝试加载 $modelFile (${bytes.size / 1024 / 1024} MB)")

            // 依次尝试三种执行后端
            val providers = listOf("NNAPI", "XNNPACK", "CPU")
            for (provider in providers) {
                val s = tryBuildSession(ortEnv, bytes, provider)
                if (s != null) {
                    session = s
                    activeProvider = provider
                    Log.i(TAG, "✅ 加载成功：$modelFile / $provider")
                    return
                }
            }
        }

        Log.e(TAG, "❌ 所有模型和执行后端都失败了")
    }

    private fun tryBuildSession(
        ortEnv: OrtEnvironment,
        modelBytes: ByteArray,
        provider: String,
    ): OrtSession? = try {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        when (provider) {
            "NNAPI" -> try {
                opts.addNnapi()
            } catch (e: Throwable) {
                Log.w(TAG, "NNAPI 不可用: ${e.message}")
                throw e
            }
            "XNNPACK" -> try {
                opts.addXnnpack(Collections.emptyMap())
            } catch (e: Throwable) {
                Log.w(TAG, "XNNPACK 不可用: ${e.message}")
                throw e
            }
            else -> opts.setIntraOpNumThreads(2)
        }

        ortEnv.createSession(modelBytes, opts)
    } catch (e: Throwable) {
        Log.w(TAG, "$provider 后端失败: ${e.message}")
        null
    }

    /**
     * 对一帧画面做检测。
     *
     * @param frame 屏幕截图（任意尺寸）
     * @return 检测结果，坐标为归一化 0..1
     */
    fun detect(frame: Bitmap): List<Detection> {
        val sess = session ?: return emptyList()
        val ortEnv = env ?: return emptyList()

        if (frame.width <= 0 || frame.height <= 0) return emptyList()

        // ---------- 1. letterbox 预处理 ----------
        val scale = INPUT_SIZE.toFloat() / maxOf(frame.width, frame.height)
        val lb = prepareLetterbox(frame, scale)

        // letterbox 两侧的黑边宽度，后面映射坐标要减掉
        val padX = (INPUT_SIZE - frame.width * scale) / 2f
        val padY = (INPUT_SIZE - frame.height * scale) / 2f

        // ---------- 2. 转成模型输入张量 ----------
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        lb.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buf = inputBuffer ?: FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
            .also { inputBuffer = it }
        buf.rewind()

        // CHW 排列：先 R 通道，再 G，再 B
        for (c in 0..2) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> (p shr 16) and 0xFF
                    1 -> (p shr 8) and 0xFF
                    else -> p and 0xFF
                }
                buf.put(v * INV_255)
            }
        }
        buf.rewind()

        // ---------- 3. 推理 ----------
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val result = try {
            OnnxTensor.createTensor(ortEnv, buf, shape).use { tensor ->
                sess.run(Collections.singletonMap("images", tensor)).use { out ->
                    parseOutput(out, scale, padX, padY, frame.width, frame.height)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "推理失败: ${e.message}")
            return emptyList()
        }

        return result
    }

    /** letterbox：等比缩放并居中，空白处涂黑 */
    private fun prepareLetterbox(frame: Bitmap, scale: Float): Bitmap {
        var lb = letterboxBitmap
        if (lb == null) {
            lb = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            letterboxBitmap = lb
        }
        val canvas = android.graphics.Canvas(lb)
        canvas.drawColor(android.graphics.Color.BLACK)

        val dw = frame.width * scale
        val dh = frame.height * scale
        val left = (INPUT_SIZE - dw) / 2f
        val top = (INPUT_SIZE - dh) / 2f

        val dst = android.graphics.RectF(left, top, left + dw, top + dh)
        val src = android.graphics.Rect(0, 0, frame.width, frame.height)
        canvas.drawBitmap(frame, src, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return lb
    }

    /**
     * 解析模型输出。
     *
     * YOLOv8 输出形状：[1, 23, N]，其中 N 是候选框数量。
     * 每列 23 个数：cx, cy, w, h, conf, cls0..cls17
     */
    private fun parseOutput(
        out: OrtSession.Result,
        scale: Float,
        padX: Float,
        padY: Float,
        frameW: Int,
        frameH: Int,
    ): List<Detection> {
        val tensor = out.get(0) as? OnnxTensor ?: return emptyList()
        val value = tensor.value

        // 兼容不同输出布局，统一成 [features][candidates]
        val flat: FloatArray
        var features: Int
        var candidates: Int

        when (val v = value) {
            is Array<*> -> {
                // [1][23][N]
                val first = v[0] as? Array<*> ?: return emptyList()
                features = first.size
                val row0 = first[0] as? FloatArray ?: return emptyList()
                candidates = row0.size
                flat = FloatArray(features * candidates)
                for (f in 0 until features) {
                    val row = first[f] as? FloatArray ?: continue
                    System.arraycopy(row, 0, flat, f * candidates, minOf(row.size, candidates))
                }
            }
            else -> return emptyList()
        }

        if (features < 5) return emptyList()

        val raw = ArrayList<Detection>(64)

        for (i in 0 until candidates) {
            // 置信度在索引 4
            val conf = flat[4 * candidates + i]

            // 找分数最高的类别
            var bestCls = -1
            var bestClsScore = 0f
            for (c in 0 until NUM_CLASSES) {
                if (features <= 5 + c) break
                val s = flat[(5 + c) * candidates + i]
                if (s > bestClsScore) {
                    bestClsScore = s
                    bestCls = c
                }
            }
            if (bestCls < 0) continue

            val score = conf * bestClsScore
            val label = CLASS_NAMES.getOrNull(bestCls) ?: continue

            // 每个类别自己的门槛
            val minScore = MIN_SCORE[label] ?: DEFAULT_MIN_SCORE
            if (score < minScore) continue

            // cx, cy, w, h 都在 letterbox 坐标系里
            val cx = flat[0 * candidates + i]
            val cy = flat[1 * candidates + i]
            val w = flat[2 * candidates + i]
            val h = flat[3 * candidates + i]

            if (w <= 1f || h <= 1f) continue

            // 先把 letterbox 里的像素坐标算出来
            val lx1 = cx - w / 2f
            val ly1 = cy - h / 2f
            val lx2 = cx + w / 2f
            val ly2 = cy + h / 2f

            // 减去黑边 -> 除以缩放比 -> 得到原图像素坐标
            val px1 = (lx1 - padX) / scale
            val py1 = (ly1 - padY) / scale
            val px2 = (lx2 - padX) / scale
            val py2 = (ly2 - padY) / scale

            // 归一化到 0..1（OverlayView 按屏幕宽高还原）
            val x1 = px1 / frameW
            val y1 = py1 / frameH
            val x2 = px2 / frameW
            val y2 = py2 / frameH

            raw.add(
                Detection(
                    label = label,
                    score = score,
                    left = x1.coerceIn(0f, 1f),
                    top = y1.coerceIn(0f, 1f),
                    right = x2.coerceIn(0f, 1f),
                    bottom = y2.coerceIn(0f, 1f),
                )
            )
        }

        return applyNms(raw, NMS_IOU)
    }

    /** 非极大值抑制：同一个目标别框好几次 */
    private fun applyNms(dets: List<Detection>, iouThreshold: Float): List<Detection> {
        if (dets.size <= 1) return dets
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>(sorted.size)

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { it.label == best.label && it.iou(best) > iouThreshold }
        }
        return keep
    }

    fun close() {
        session?.close()
        session = null
        letterboxBitmap?.recycle()
        letterboxBitmap = null
        inputBuffer = null
    }
}

/** 引擎配置 */
data class DetectorConfig(
    val confidenceThreshold: Float = 0.5f,
    val minDetectionSize: Int = 12,
)
