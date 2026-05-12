package com.example.myapplication

import android.graphics.PointF
import android.graphics.RectF

/**
 * 구도(Composition)에 따라 가이드 박스 위치를 계산하는 유틸리티 오브젝트.
 *
 * [기여] 구도 기반 가이드 모드 신규 도입.
 *
 * ## 좌표계
 * 이 프로젝트의 MediaPipe는 카메라 프레임을 세로(portrait)로 회전한 뒤 감지한다.
 * 바운딩 박스는 480×640 픽셀 좌표계 기준이다.
 *
 * ## 교차점 좌표 (480×640 기준)
 * - 중앙   : (240, 320)
 * - 삼분할 : (160, 213), (320, 213), (160, 427), (320, 427)
 * - 황금비 : (183, 245), (296, 245), (183, 395), (296, 395)
 *   (480 × 0.382 ≈ 183.4,  480 × 0.618 ≈ 295.6,
 *    640 × 0.382 ≈ 244.5,  640 × 0.618 ≈ 395.5)
 */
object CompositionGuideCalculator {

    private const val IMAGE_W = 480f  // 회전 후 이미지 너비 (픽셀)
    private const val IMAGE_H = 640f  // 회전 후 이미지 높이 (픽셀)

    // ── 구도별 교차점 목록 ──────────────────────────────────────────────────────

    private val CENTER_POINTS = listOf(
        PointF(IMAGE_W / 2f, IMAGE_H / 2f)       // (240, 320)
    )

    private val THIRDS_POINTS = listOf(
        PointF(IMAGE_W / 3f,       IMAGE_H / 3f),        // 좌상 (160, 213)
        PointF(IMAGE_W * 2f / 3f,  IMAGE_H / 3f),        // 우상 (320, 213)
        PointF(IMAGE_W / 3f,       IMAGE_H * 2f / 3f),   // 좌하 (160, 427)
        PointF(IMAGE_W * 2f / 3f,  IMAGE_H * 2f / 3f)    // 우하 (320, 427)
    )

    private val GOLDEN_POINTS = listOf(
        PointF(IMAGE_W * 0.382f, IMAGE_H * 0.382f),  // 좌상 (≈183, ≈245)
        PointF(IMAGE_W * 0.618f, IMAGE_H * 0.382f),  // 우상 (≈296, ≈245)
        PointF(IMAGE_W * 0.382f, IMAGE_H * 0.618f),  // 좌하 (≈183, ≈395)
        PointF(IMAGE_W * 0.618f, IMAGE_H * 0.618f)   // 우하 (≈296, ≈395)
    )

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 피사체 바운딩 박스와 구도를 받아 가이드 박스 위치를 계산한다.
     *
     * 피사체 중심과 가장 가까운 교차점을 찾고,
     * 그 교차점을 중심으로 피사체와 동일한 크기의 박스를 생성한다.
     *
     * @param subjectBox  피사체 바운딩 박스 (480×640 픽셀 좌표계)
     * @param composition 선택된 구도 유형
     * @return 가이드 박스 (480×640 픽셀 좌표계, 이미지 경계 내로 클램핑됨)
     */
    fun computeTargetBox(subjectBox: RectF, composition: Composition): RectF {
        val center  = PointF(subjectBox.centerX(), subjectBox.centerY())
        val nearest = findNearest(center, getIntersectionPoints(composition))
        val halfW   = subjectBox.width()  / 2f
        val halfH   = subjectBox.height() / 2f
        val raw     = RectF(nearest.x - halfW, nearest.y - halfH,
                            nearest.x + halfW, nearest.y + halfH)
        return clampToImage(raw)
    }

    /**
     * 선택된 구도의 교차점 목록을 반환한다.
     * GuideOverlayView에서 교차점 마커를 그릴 때 사용한다.
     */
    fun getIntersectionPoints(composition: Composition): List<PointF> = when (composition) {
        Composition.CENTER         -> CENTER_POINTS
        Composition.RULE_OF_THIRDS -> THIRDS_POINTS
        Composition.GOLDEN_RATIO   -> GOLDEN_POINTS
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /** 유클리드 거리 제곱으로 가장 가까운 교차점을 탐색한다. */
    private fun findNearest(center: PointF, points: List<PointF>): PointF =
        points.minByOrNull { p ->
            val dx = p.x - center.x; val dy = p.y - center.y; dx * dx + dy * dy
        } ?: points.first()

    /** 박스가 480×640 이미지 영역을 벗어나지 않도록 위치를 클램핑한다. */
    private fun clampToImage(box: RectF): RectF {
        val w    = box.width()
        val h    = box.height()
        val left = box.left.coerceIn(0f, IMAGE_W - w)
        val top  = box.top.coerceIn(0f, IMAGE_H - h)
        return RectF(left, top, left + w, top + h)
    }
}
