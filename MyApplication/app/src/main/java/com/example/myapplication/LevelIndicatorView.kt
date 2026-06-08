package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class LevelIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var angleDeg: Float = 0f
    private val dp = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * dp; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * dp
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
    }

    fun update(angle: Float) {
        if (angleDeg != angle) {
            angleDeg = angle
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx          = width / 2f
        val cy          = height * 0.52f
        val lineHalfLen = 40f * dp
        val cornerR     = 14f * dp

        val color = levelColor(angleDeg)
        linePaint.color = color
        dotPaint.color  = color

        canvas.drawRoundRect(
            cx - lineHalfLen - 10f * dp, 2f * dp,
            cx + lineHalfLen + 10f * dp, height.toFloat() - 2f * dp,
            cornerR, cornerR, bgPaint
        )

        // 가장 가까운 수평 기준점(0° 또는 ±180°)으로부터의 편차만큼 회전
        // raw 각도를 그대로 쓰면 180° 부근에서 인디케이터가 뒤집히므로 visualAngle() 사용
        canvas.save()
        canvas.rotate(visualAngle(angleDeg), cx, cy)
        canvas.drawLine(cx - lineHalfLen, cy, cx + lineHalfLen, cy, linePaint)
        canvas.drawCircle(cx, cy, 5f * dp, dotPaint)
        canvas.restore()

        // 0.05° 미만은 "0.0°"로 고정 — 미세 진동에 의한 숫자 흔들림 방지
        val dist  = distanceToLevel(angleDeg)
        val label = if (dist < 0.05f) "0.0°" else "%.1f°".format(dist)
        canvas.drawText(label, cx, height.toFloat() - 4f * dp, textPaint)
    }

    // 0°와 ±180° 두 수평 기준점 중 가까운 쪽까지의 거리
    private fun distanceToLevel(angle: Float): Float {
        val a = abs(angle)
        return minOf(a, abs(a - 180f))
    }

    // 가장 가까운 수평 기준점으로부터의 부호 있는 편차 (인디케이터 회전량)
    private fun visualAngle(angle: Float): Float = when {
        abs(angle) <= 90f -> angle
        angle >= 0f       -> angle - 180f
        else              -> angle + 180f
    }

    private fun levelColor(angle: Float): Int = when {
        distanceToLevel(angle) <= 3f -> Color.parseColor("#69F0AE")  // 수평 (±3° 이내)
        distanceToLevel(angle) <= 5f -> Color.parseColor("#FFD740")  // 경미한 기울기
        else                         -> Color.parseColor("#FF5252")  // 심한 기울기
    }
}
