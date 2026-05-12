package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * MediaPipe Object Detector의 실시간 감지 결과를 카메라 프리뷰 위에 오버레이하는 커스텀 View.
 *
 * ## 동작 모드
 * - **일반 모드** ([trackedBox] == null): 감지된 모든 박스를 그린다.
 *   [selectedIndex]에 해당하는 박스는 빨간 강조 스타일로 표시된다.
 * - **추적 모드** ([trackedBox] != null): [trackedBox] 하나만 그린다.
 *   MainActivity가 [FRAME_UPDATE_INTERVAL] 프레임마다 [setTrackedBox]를 호출하여
 *   박스 위치·크기가 급격히 떨리지 않고 안정적으로 표시된다.
 *
 * ## 좌표계
 * 바운딩 박스는 480×640 픽셀 좌표계 기준.
 * View 크기로 단순 비율 변환하여 그린다:
 *   screenX = imageX / 480 * viewWidth
 *   screenY = imageY / 640 * viewHeight
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    // ── 이미지 좌표계 기준 크기 ─────────────────────────────────────────────────

    private val MODEL_W = 480f
    private val MODEL_H = 640f

    // ── Paint ──────────────────────────────────────────────────────────────────

    /** 기본 박스 테두리 (흰색) — 일반 모드에서 미선택 박스에 사용 */
    private val boxPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
    }

    /** 선택/추적 박스 테두리 (빨간색, 더 두꺼움) */
    private val selectedBoxPaint = Paint().apply {
        color = Color.parseColor("#FFE94560")
        style = Paint.Style.STROKE; strokeWidth = 10f; isAntiAlias = true
    }

    /** 선택/추적 박스 내부 채우기 (빨간 반투명) */
    private val selectedFillPaint = Paint().apply {
        color = Color.parseColor("#33E94560"); style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; isAntiAlias = true
    }

    // ── 내부 상태 ──────────────────────────────────────────────────────────────

    private var results: ObjectDetectorResult? = null

    /** 일반 모드에서 강조할 감지 결과의 인덱스 (-1: 미선택) */
    var selectedIndex: Int = -1

    /**
     * 추적 모드에서 그릴 박스 (480×640 이미지 좌표계).
     * null이면 일반 모드(전체 박스 표시), null이 아니면 추적 모드(이 박스만 표시).
     *
     * [기여] 선택된 피사체만 표시하는 추적 모드 도입.
     */
    private var trackedBox: RectF? = null

    /** 추적 모드에서 박스 위에 표시할 라벨 문자열 */
    private var trackedLabel: String? = null

    /**
     * 박스 터치 이벤트 콜백.
     *
     * [기여] 구도 모드 신규 도입 — 피사체 선택 경로.
     * 파라미터: (리스트 인덱스, 바운딩 박스 480×640, 라벨 문자열)
     */
    var onDetectionSelected: ((index: Int, box: RectF, label: String) -> Unit)? = null

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 새 감지 결과를 반영한다.
     * 추적 모드([trackedBox] != null)에서는 결과가 업데이트되어도
     * [onDraw]가 [trackedBox]만 그리므로 시각적 변화가 없다.
     */
    fun setResults(detectionResults: ObjectDetectorResult) {
        results = detectionResults
        invalidate()
    }

    /**
     * 추적 모드로 전환하고 표시할 박스를 지정한다.
     *
     * [기여] 선택된 피사체만 표시하는 추적 모드 도입.
     * MainActivity의 [FRAME_UPDATE_INTERVAL] 프레임 주기로 호출되어
     * 박스 위치·크기가 급격히 변동하지 않도록 한다.
     *
     * @param box   표시할 박스 (480×640 이미지 좌표계). null이면 일반 모드로 복귀.
     * @param label 박스 위에 표시할 라벨. null이면 라벨만 생략.
     */
    fun setTrackedBox(box: RectF?, label: String?) {
        trackedBox  = box
        trackedLabel = label
        invalidate()
    }

    /**
     * 선택 및 추적 상태를 모두 초기화하고 일반 모드(전체 박스 표시)로 복귀한다.
     *
     * [기여] 포커스 해제 버튼 기능 도입.
     */
    fun clearSelection() {
        selectedIndex = -1
        trackedBox    = null
        trackedLabel  = null
        invalidate()
    }

    // ── 그리기 ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val tracked = trackedBox
        if (tracked != null) {
            // ── 추적 모드: 선택된 피사체 박스 하나만 그린다 ─────────────────────
            val rect = tracked.toScreen()
            canvas.drawRect(rect, selectedFillPaint)
            canvas.drawRect(rect, selectedBoxPaint)
            val label = trackedLabel ?: ""
            canvas.drawText(label, rect.left + 10f, maxOf(rect.top + 50f, 50f), textPaint)
            return
        }

        // ── 일반 모드: 감지된 모든 박스를 그린다 ──────────────────────────────
        results?.let { detectorResult ->
            detectorResult.detections().forEachIndexed { index, detection ->
                val rect = RectF(detection.boundingBox()).toScreen()

                if (index == selectedIndex) {
                    // 선택된 박스: 반투명 채우기 + 빨간 테두리
                    canvas.drawRect(rect, selectedFillPaint)
                    canvas.drawRect(rect, selectedBoxPaint)
                } else {
                    canvas.drawRect(rect, boxPaint)
                }

                val category = detection.categories().firstOrNull()
                val text = "${category?.categoryName() ?: "?"} " +
                           "(${((category?.score() ?: 0f) * 100).toInt()}%)"
                canvas.drawText(text, rect.left + 10f, maxOf(rect.top + 50f, 50f), textPaint)
            }
        }
    }

    // ── 터치 처리 ──────────────────────────────────────────────────────────────

    /**
     * 터치한 위치에 바운딩 박스가 있으면 [onDetectionSelected] 콜백을 호출한다.
     *
     * [기여] 구도 모드 신규 도입 — 피사체 터치 선택 기능.
     * 터치 좌표를 480×640 이미지 좌표로 역변환 후 각 박스의 포함 여부를 확인한다.
     * 추적 모드 중에도 터치가 오면 새 피사체를 선택할 수 있도록 항상 [results]를 탐색한다.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        // 추적 모드에서는 다른 피사체 선택을 차단한다.
        // 포커스를 변경하려면 반드시 "포커스 해제" 버튼을 먼저 눌러야 한다.
        if (trackedBox != null) return true

        // 화면 좌표 → 480×640 이미지 좌표 역변환
        val imageX = event.x / width  * MODEL_W
        val imageY = event.y / height * MODEL_H

        results?.detections()?.forEachIndexed { index, detection ->
            if (detection.boundingBox().contains(imageX, imageY)) {
                selectedIndex = index
                val box   = RectF(detection.boundingBox())
                val label = detection.categories().firstOrNull()?.categoryName() ?: "unknown"
                onDetectionSelected?.invoke(index, box, label)
                invalidate()
                return true
            }
        }

        return true  // 박스 외 영역도 소비 — ACTION_DOWN이 항상 이 View에 전달되도록 보장
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /** 480×640 이미지 좌표 → View 화면 좌표 변환 */
    private fun RectF.toScreen() = RectF(
        left   / MODEL_W * width,
        top    / MODEL_H * height,
        right  / MODEL_W * width,
        bottom / MODEL_H * height
    )
}
