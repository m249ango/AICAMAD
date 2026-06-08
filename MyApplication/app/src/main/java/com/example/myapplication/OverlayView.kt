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

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private val MODEL_W = 480f
    private val MODEL_H = 640f

    private val boxPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
    }
    private val selectedBoxPaint = Paint().apply {
        color = Color.parseColor("#FFE94560")
        style = Paint.Style.STROKE; strokeWidth = 10f; isAntiAlias = true
    }
    private val selectedFillPaint = Paint().apply {
        color = Color.parseColor("#33E94560"); style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; isAntiAlias = true
    }

    private var results: ObjectDetectorResult? = null
    var selectedIndex: Int = -1
    private var trackedBox: RectF? = null
    private var trackedLabel: String? = null

    // (인덱스, 박스 480×640, 라벨) — 단일 객체 터치 시 호출
    var onDetectionSelected: ((index: Int, box: RectF, label: String) -> Unit)? = null
    // 복수 객체 중첩 터치 시 호출
    var onMultipleDetectionsFound: ((
        candidates: List<DetectionCandidate>,
        touchViewX: Float,
        touchViewY: Float
    ) -> Unit)? = null

    fun setResults(detectionResults: ObjectDetectorResult) {
        results = detectionResults
        invalidate()
    }

    fun setTrackedBox(box: RectF?, label: String?) {
        trackedBox   = box
        trackedLabel = label
        invalidate()
    }

    fun clearSelection() {
        selectedIndex = -1
        trackedBox    = null
        trackedLabel  = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val tracked = trackedBox
        if (tracked != null) {
            // 추적 모드: 선택된 피사체 박스만 표시
            val rect = tracked.toScreen()
            canvas.drawRect(rect, selectedFillPaint)
            canvas.drawRect(rect, selectedBoxPaint)
            canvas.drawText(trackedLabel ?: "", rect.left + 10f, maxOf(rect.top + 50f, 50f), textPaint)
            return
        }

        // 일반 모드: 모든 감지 박스 표시
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (trackedBox != null) return true  // 추적 모드 중 새 선택 차단

        // 화면 좌표 → 480×640 이미지 좌표 역변환
        val imageX = event.x / width  * MODEL_W
        val imageY = event.y / height * MODEL_H

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
            0    -> { /* 빈 영역 터치 */ }
            1    -> {
                val hit = hits[0]
                selectedIndex = hit.index
                onDetectionSelected?.invoke(hit.index, hit.box, hit.label)
                invalidate()
            }
            else -> onMultipleDetectionsFound?.invoke(hits, event.x, event.y)
        }

        return true
    }

    /** 480×640 이미지 좌표 → View 화면 좌표 변환 */
    private fun RectF.toScreen() = RectF(
        left   / MODEL_W * width,
        top    / MODEL_H * height,
        right  / MODEL_W * width,
        bottom / MODEL_H * height
    )
}

data class DetectionCandidate(
    val index: Int,
    val label: String,
    val score: Int,
    val box:   RectF
)
