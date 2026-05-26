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
 *   MainActivity가 N 프레임마다 [setTrackedBox]로 위치를 갱신하므로
 *   화면 박스가 급격히 떨리지 않고 부드럽게 유지된다.
 *
 * ## 터치 선택 로직
 * - 터치 지점에 객체가 1개 → [onDetectionSelected] 콜백으로 즉시 선택.
 * - 터치 지점에 객체가 2개 이상 → [onMultipleDetectionsFound] 콜백으로 후보 목록 전달.
 *   MainActivity에서 [ListPopupWindow]를 띄워 사용자가 원하는 객체를 고를 수 있도록 한다.
 * - 추적 모드 중에는 새 선택 터치가 차단된다.
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
     */
    private var trackedBox: RectF? = null

    /** 추적 모드에서 박스 위에 표시할 라벨 문자열 */
    private var trackedLabel: String? = null

    // ── 콜백 ──────────────────────────────────────────────────────────────────

    /**
     * 터치 지점에 객체가 정확히 1개 있을 때 호출되는 콜백.
     * 파라미터: (리스트 인덱스, 바운딩 박스 480×640, 라벨 문자열)
     */
    var onDetectionSelected: ((index: Int, box: RectF, label: String) -> Unit)? = null

    /**
     * 터치 지점에 객체가 2개 이상 겹쳐 있을 때 호출되는 콜백.    
     *
     * MainActivity는 이 콜백을 받아 [ListPopupWindow]로 선택 UI를 표시한다.
     *
     * @param candidates  터치 지점을 포함하는 모든 감지 후보 목록
     * @param touchViewX  OverlayView 내 터치 X 좌표 (화면 픽셀, 팝업 위치 계산용)
     * @param touchViewY  OverlayView 내 터치 Y 좌표 (화면 픽셀, 팝업 위치 계산용)
     */
    var onMultipleDetectionsFound: ((
        candidates: List<DetectionCandidate>,
        touchViewX: Float,
        touchViewY: Float
    ) -> Unit)? = null

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 새 감지 결과를 반영한다.
     * 추적 모드([trackedBox] != null)에서는 [results]가 업데이트되어도
     * [onDraw]에서 [trackedBox]만 그리므로 시각적 변화가 없다.
     */
    fun setResults(detectionResults: ObjectDetectorResult) {
        results = detectionResults
        invalidate()
    }

    /**
     * 추적 모드로 전환하고 표시할 박스를 지정한다.
     *
     * MainActivity의 [FRAME_UPDATE_INTERVAL] 프레임 주기로 호출되어
     * 박스 위치·크기가 급격히 변동하지 않도록 한다.
     *
     * @param box   표시할 박스 (480×640 이미지 좌표계). null이면 일반 모드로 복귀.
     * @param label 박스 위에 표시할 라벨. null이면 라벨 없음.
     */
    fun setTrackedBox(box: RectF?, label: String?) {
        trackedBox   = box
        trackedLabel = label
        invalidate()
    }

    /**
     * 선택 및 추적 상태를 모두 초기화하고 일반 모드(전체 박스 표시)로 복귀한다.
     * 포커스 해제 버튼 클릭 시 MainActivity에서 호출한다.
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
     * 터치 지점의 바운딩 박스를 수집하여 후보 수에 따라 처리를 분기한다.
     *
     * - 0개: 빈 영역 터치 — 아무 동작 없음
     * - 1개: [onDetectionSelected]로 즉시 선택
     * - 2개 이상: [onMultipleDetectionsFound]로 후보 목록 전달
     *
     * 추적 모드([trackedBox] != null) 중에는 새 선택을 차단한다.
     * 포커스를 변경하려면 "포커스 해제" 버튼을 먼저 눌러야 한다.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        // 추적 모드에서는 다른 피사체 선택을 차단한다
        if (trackedBox != null) return true

        // 화면 좌표 → 480×640 이미지 좌표 역변환
        val imageX = event.x / width  * MODEL_W
        val imageY = event.y / height * MODEL_H

        // 터치 지점을 포함하는 모든 박스를 수집
        val hits = mutableListOf<DetectionCandidate>()
        results?.detections()?.forEachIndexed { index, detection ->
            if (detection.boundingBox().contains(imageX, imageY)) {
                val category = detection.categories().firstOrNull()
                hits.add(
                    DetectionCandidate(
                        index = index,
                        label = category?.categoryName() ?: "unknown",
                        score = ((category?.score() ?: 0f) * 100).toInt(),
                        box   = RectF(detection.boundingBox())
                    )
                )
            }
        }

        when (hits.size) {
            0    -> { /* 빈 영역 터치 — 무시 */ }
            1    -> {
                // 단일 객체 — 즉시 선택
                val hit = hits[0]
                selectedIndex = hit.index
                onDetectionSelected?.invoke(hit.index, hit.box, hit.label)
                invalidate()
            }
            else -> {
                // 복수 객체 중첩 — 선택 UI를 MainActivity에 위임
                onMultipleDetectionsFound?.invoke(hits, event.x, event.y)
            }
        }

        return true  // ACTION_DOWN을 항상 소비하여 이 View가 이후 이벤트도 수신하도록 보장
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

/**
 * 터치 지점에 중첩된 감지 후보 하나를 나타내는 데이터 클래스.
 *
 * [OverlayView.onMultipleDetectionsFound] 콜백에서 사용된다.
 *
 * @param index 감지 결과 리스트에서의 인덱스 ([OverlayView.selectedIndex] 설정에 사용)
 * @param label 카테고리 이름 (예: "cat", "person")
 * @param score 신뢰도 점수 0~100 (팝업 항목 표시용)
 * @param box   바운딩 박스 (480×640 이미지 좌표계)
 */
data class DetectionCandidate(
    val index: Int,
    val label: String,
    val score: Int,
    val box:   RectF
)
