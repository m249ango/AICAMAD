package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private val boxPaint = Paint()
    private val textPaint = Paint()

    init {
        boxPaint.color = Color.WHITE // 흰색
        boxPaint.style = Paint.Style.STROKE // 테두리만
        boxPaint.strokeWidth = 8f // 선 두께
        boxPaint.isAntiAlias = true

        textPaint.color = Color.WHITE // 흰색 글자
        textPaint.textSize = 40f
    }

    fun setResults(detectionResults: ObjectDetectorResult) {
        results = detectionResults
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        results?.let { detectorResult ->
            for (detection in detectorResult.detections()) {
                val boundingBox = detection.boundingBox()

                // 4:3 해상도
                val modelWidth = 480f
                val modelHeight = 640f

                // 1. 픽셀 좌표를 비율로 변환
                val leftRatio = boundingBox.left / modelWidth
                val topRatio = boundingBox.top / modelHeight
                val rightRatio = boundingBox.right / modelWidth
                val bottomRatio = boundingBox.bottom / modelHeight

                // 2. 현재 뷰 크기에 맞춰 픽셀화
                val drawLeft = leftRatio * width
                val drawTop = topRatio * height
                val drawRight = rightRatio * width
                val drawBottom = bottomRatio * height

                val rect = RectF(drawLeft, drawTop, drawRight, drawBottom)

                canvas.drawRect(rect, boxPaint)
                val category = detection.categories()[0]
                val text = "${category.categoryName()} (${(category.score() * 100).toInt()}%)"
                canvas.drawText(text, drawLeft + 10f, drawTop + 50f, textPaint)
            }
        }
    }
}