package com.example.smoothcensor

/**
 * 时序平滑器 —— 这是解决"一闪一闪"的核心。
 *
 * 原版软件的做法：检测到就画，检测不到就删 → 结果闪。
 *
 * 这里改成三个机制：
 *   1. 多帧投票：连续 N 帧出现才算数（消除偶发误检）
 *   2. 消失缓冲：检测不到时先保留几帧再删（消除偶发漏检）
 *   3. 指数平滑：框的位置渐进移动，而不是瞬移（视觉顺滑）
 *
 * 效果：牺牲大约 2~3 帧的响应速度，换来基本不闪。
 */
class TemporalSmoother(
    /** 连续命中多少帧才认为真的存在 */
    private val confirmFrames: Int = 2,
    /** 丢失后保留多少帧才移除 */
    private val retainFrames: Int = 6,
    /** 位置平滑系数：越小越平滑但越迟钝，范围 0..1 */
    private val positionAlpha: Float = 0.45f,
    /** 判定为"同一个目标"的 IOU 阈值 */
    private val matchIou: Float = 0.20f,
) {
    /** 一条被跟踪的目标 */
    class Track(
        var detection: Detection,
        var hitStreak: Int = 1,
        var missStreak: Int = 0,
        var smoothedLeft: Float = 0f,
        var smoothedTop: Float = 0f,
        var smoothedRight: Float = 0f,
        var smoothedBottom: Float = 0f,
    ) {
        /** 是否已经确认（可以画出来了） */
        fun isConfirmed(need: Int) = hitStreak >= need

        /** 是否已经该移除 */
        fun isExpired(limit: Int) = missStreak >= limit
    }

    private val tracks = ArrayList<Track>()

    /** 当前所有"确认存在"的框，用于绘制 */
    val visibleDetections: List<Detection>
        get() = tracks.filter { it.isConfirmed(confirmFrames) }.map { t ->
            Detection(
                label = t.detection.label,
                score = t.detection.score,
                left = t.smoothedLeft,
                top = t.smoothedTop,
                right = t.smoothedRight,
                bottom = t.smoothedBottom,
            )
        }

    /**
     * 用新一帧的检测结果更新状态。
     *
     * @param newDetections 本帧 AI 检测到的框
     */
    fun update(newDetections: List<Detection>) {
        val matched = BooleanArray(newDetections.size)

        // ---- 1. 给已有的 track 找匹配 ----
        for (track in tracks) {
            var bestIdx = -1
            var bestIou = matchIou

            newDetections.forEachIndexed { idx, det ->
                if (matched[idx]) return@forEachIndexed
                if (det.label != track.detection.label) return@forEachIndexed
                val iou = det.iou(track.detection)
                if (iou > bestIou) {
                    bestIou = iou
                    bestIdx = idx
                }
            }

            if (bestIdx >= 0) {
                // 命中：重置丢失计数，累加命中
                val det = newDetections[bestIdx]
                matched[bestIdx] = true
                track.detection = det
                track.hitStreak++
                track.missStreak = 0
                smoothToward(track, det)
            } else {
                // 没命中：累加丢失计数
                track.missStreak++
                // 丢失期间不移动，保持最后位置
            }
        }

        // ---- 2. 移除过期目标 ----
        tracks.removeAll { it.isExpired(retainFrames) }

        // ---- 3. 新增没被匹配上的检测 ----
        // 直接从实际位置和尺寸开始。
        // （试过"从中心点长出来"的做法，视觉上反而像果冻，
        //   所以还是老老实实按实际框出现，靠 confirmFrames 控制误检。）
        newDetections.forEachIndexed { idx, det ->
            if (matched[idx]) return@forEachIndexed
            tracks.add(
                Track(
                    detection = det,
                    smoothedLeft = det.left,
                    smoothedTop = det.top,
                    smoothedRight = det.right,
                    smoothedBottom = det.bottom,
                )
            )
        }
    }

    /** 指数平滑：新位置 = 旧位置 + alpha × 目标位置与旧位置的差 */
    private fun smoothToward(track: Track, target: Detection) {
        val a = positionAlpha.coerceIn(0.05f, 1f)
        track.smoothedLeft += a * (target.left - track.smoothedLeft)
        track.smoothedTop += a * (target.top - track.smoothedTop)
        track.smoothedRight += a * (target.right - track.smoothedRight)
        track.smoothedBottom += a * (target.bottom - track.smoothedBottom)
    }

    /**
     * 画面大幅切换时调用（比如翻页、切换应用）。
     * 立刻清空，避免旧框盖在新画面上。
     */
    fun reset() {
        tracks.clear()
    }

    /** 当前跟踪数量，用于调试显示 */
    val trackCount: Int get() = tracks.size
}
