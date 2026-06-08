package com.example.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val COLOR_IDLE      = Color.WHITE
    private val COLOR_MATCHED   = Color.parseColor("#FF00E676")
    private val COLOR_RECOMMEND = Color.parseColor("#FF69F0AE")

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val markerCirclePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val markerCrossPaint  = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000"); style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint().apply {
        textSize = 42f; isAntiAlias = true; isFakeBoldText = true
    }

    private var targetBox: RectF?        = null
    private var composition: Composition? = null
    private var matchState: MatchState   = MatchState.IDLE

    private val MODEL_W = 480f
    private val MODEL_H = 640f

    private var pulseAlpha = 1f
    private val pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
        duration = 700L; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator()
        addUpdateListener { pulseAlpha = it.animatedValue as Float; invalidate() }
    }

    // matchState를 IDLE로 리셋 — 피사체 선택 후 구도 최초 설정 시에만 호출
    fun setGuide(box: RectF, composition: Composition) {
        this.targetBox   = box
        this.composition = composition
        setMatchState(MatchState.IDLE)
    }

    // matchState 유지하며 박스 위치만 갱신 — 추적 중 매 N 프레임 호출
    // setGuide()를 쓰면 매 N 프레임마다 타이머가 초기화되어 2초 조건 미충족
    fun updateGuideBox(box: RectF, composition: Composition) {
        this.targetBox   = box
        this.composition = composition
        invalidate()
    }

    fun setMatchState(state: MatchState) {
        matchState = state
        if (state == MatchState.MATCHED) {
            if (!pulseAnimator.isRunning) pulseAnimator.start()
        } else {
            pulseAnimator.cancel(); pulseAlpha = 1f
        }
        invalidate()
    }

    fun clearGuide() {
        targetBox = null; composition = null
        pulseAnimator.cancel(); pulseAlpha = 1f
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) pulseAnimator.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); pulseAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box  = targetBox  ?: return
        val comp = composition ?: return

        val color = when (matchState) {
            MatchState.IDLE      -> COLOR_IDLE
            MatchState.MATCHED   -> COLOR_MATCHED
            MatchState.RECOMMEND -> COLOR_RECOMMEND
        }
        // MATCHED: 펄스(0.4~1.0), 나머지: 완전 불투명
        val alpha = if (matchState == MatchState.MATCHED) (pulseAlpha * 255).toInt() else 255

        val screenBox = box.toScreen()

        // ① 박스 내부 채우기 (alpha × 12%)
        fillPaint.color = color; fillPaint.alpha = (alpha * 0.12f).toInt()
        canvas.drawRect(screenBox, fillPaint)

        // ② 박스 테두리 — RECOMMEND: 실선, 나머지: 점선
        borderPaint.color = color; borderPaint.alpha = alpha
        borderPaint.pathEffect = if (matchState == MatchState.RECOMMEND) null
                                 else DashPathEffect(floatArrayOf(20f, 10f), 0f)
        canvas.drawRect(screenBox, borderPaint)

        // ③ L자형 코너 마커
        val cornerPaint = Paint(borderPaint).apply { pathEffect = null; strokeWidth = 8f }
        drawCorners(canvas, screenBox, 30f, cornerPaint)

        // ④ 구도 교차점 마커 (원 + 십자)
        val markerAlpha = (alpha * 0.7f).toInt()
        markerCirclePaint.color = color; markerCirclePaint.alpha = markerAlpha
        markerCrossPaint.color  = color; markerCrossPaint.alpha  = markerAlpha
        for (pt in CompositionGuideCalculator.getIntersectionPoints(comp)) {
            val sx = pt.x / MODEL_W * width
            val sy = pt.y / MODEL_H * height
            canvas.drawCircle(sx, sy, 8f, markerCirclePaint)
            val arm = 18f
            canvas.drawLine(sx - arm, sy, sx + arm, sy, markerCrossPaint)
            canvas.drawLine(sx, sy - arm, sx, sy + arm, markerCrossPaint)
        }

        // ⑤ RECOMMEND 라벨
        if (matchState == MatchState.RECOMMEND) drawRecommendLabel(canvas, screenBox, color)
    }

    private fun drawRecommendLabel(canvas: Canvas, box: RectF, color: Int) {
        val label = "지금 촬영하세요!"
        val bounds = Rect()
        labelTextPaint.color = color
        labelTextPaint.getTextBounds(label, 0, label.length, bounds)
        val textX = box.left
        val textY = maxOf(box.top - 8f, labelTextPaint.textSize + 14f)
        canvas.drawRoundRect(
            RectF(textX, textY - bounds.height() - 12f,
                  textX + bounds.width() + 24f, textY + 10f),
            8f, 8f, labelBgPaint
        )
        canvas.drawText(label, textX + 12f, textY, labelTextPaint)
    }

    private fun drawCorners(canvas: Canvas, box: RectF, len: Float, paint: Paint) {
        canvas.drawLine(box.left,  box.top,    box.left + len,  box.top,          paint)
        canvas.drawLine(box.left,  box.top,    box.left,        box.top + len,    paint)
        canvas.drawLine(box.right, box.top,    box.right - len, box.top,          paint)
        canvas.drawLine(box.right, box.top,    box.right,       box.top + len,    paint)
        canvas.drawLine(box.left,  box.bottom, box.left + len,  box.bottom,       paint)
        canvas.drawLine(box.left,  box.bottom, box.left,        box.bottom - len, paint)
        canvas.drawLine(box.right, box.bottom, box.right - len, box.bottom,       paint)
        canvas.drawLine(box.right, box.bottom, box.right,       box.bottom - len, paint)
    }

    /** 480×640 이미지 좌표 → View 화면 좌표 변환 */
    private fun RectF.toScreen() = RectF(
        left   / MODEL_W * width,
        top    / MODEL_H * height,
        right  / MODEL_W * width,
        bottom / MODEL_H * height
    )
}
