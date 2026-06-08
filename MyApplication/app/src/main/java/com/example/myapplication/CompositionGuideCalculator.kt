package com.example.myapplication

import android.graphics.PointF
import android.graphics.RectF

object CompositionGuideCalculator {

    private const val IMAGE_W = 480f
    private const val IMAGE_H = 640f

    private val CENTER_POINTS = listOf(
        PointF(IMAGE_W / 2f, IMAGE_H / 2f)
    )

    private val THIRDS_POINTS = listOf(
        PointF(IMAGE_W / 3f,       IMAGE_H / 3f),
        PointF(IMAGE_W * 2f / 3f,  IMAGE_H / 3f),
        PointF(IMAGE_W / 3f,       IMAGE_H * 2f / 3f),
        PointF(IMAGE_W * 2f / 3f,  IMAGE_H * 2f / 3f)
    )

    private val GOLDEN_POINTS = listOf(
        PointF(IMAGE_W * 0.382f, IMAGE_H * 0.382f),
        PointF(IMAGE_W * 0.618f, IMAGE_H * 0.382f),
        PointF(IMAGE_W * 0.382f, IMAGE_H * 0.618f),
        PointF(IMAGE_W * 0.618f, IMAGE_H * 0.618f)
    )

    fun computeTargetBox(subjectBox: RectF, composition: Composition): RectF {
        val center  = PointF(subjectBox.centerX(), subjectBox.centerY())
        val nearest = findNearest(center, getIntersectionPoints(composition))
        val halfW   = subjectBox.width()  / 2f
        val halfH   = subjectBox.height() / 2f
        val raw     = RectF(nearest.x - halfW, nearest.y - halfH,
                            nearest.x + halfW, nearest.y + halfH)
        return clampToImage(raw)
    }

    fun getIntersectionPoints(composition: Composition): List<PointF> = when (composition) {
        Composition.CENTER         -> CENTER_POINTS
        Composition.RULE_OF_THIRDS -> THIRDS_POINTS
        Composition.GOLDEN_RATIO   -> GOLDEN_POINTS
    }

    // sqrt 생략: dist² 비교는 dist 비교와 대소 관계가 동일
    private fun findNearest(center: PointF, points: List<PointF>): PointF =
        points.minByOrNull { p ->
            val dx = p.x - center.x; val dy = p.y - center.y; dx * dx + dy * dy
        } ?: points.first()

    // 박스 크기를 유지하며 이미지 경계 내로 위치를 이동
    private fun clampToImage(box: RectF): RectF {
        val w    = box.width()
        val h    = box.height()
        val left = box.left.coerceIn(0f, IMAGE_W - w)
        val top  = box.top.coerceIn(0f, IMAGE_H - h)
        return RectF(left, top, left + w, top + h)
    }
}
