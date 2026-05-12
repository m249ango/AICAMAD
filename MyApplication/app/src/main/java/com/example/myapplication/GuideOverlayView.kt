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

/**
 * 구도 가이드 박스와 교차점 마커를 화면에 그리는 커스텀 View.
 *
 * [기여] 구도 기반 가이드 모드 신규 도입.
 *
 * ## 좌표계
 * 입력값은 480×640 이미지 픽셀 좌표계.
 * View 크기가 3:4 비율로 고정되어 있으므로 단순 비율 변환만 적용한다:
 *   screenX = imageX / 480 * viewWidth
 *   screenY = imageY / 640 * viewHeight
 *
 * ## 상태별 시각 표현
 * - IDLE      : 흰색 점선 박스
 * - MATCHED   : 초록 점선 박스 + 펄스 애니메이션
 * - RECOMMEND : 밝은 초록 실선 박스 + "지금 촬영하세요!" 라벨
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 상태별 색상 ────────────────────────────────────────────────────────────

    private val COLOR_IDLE      = Color.WHITE
    private val COLOR_MATCHED   = Color.parseColor("#FF00E676")  // 초록
    private val COLOR_RECOMMEND = Color.parseColor("#FF69F0AE")  // 밝은 초록

    // ── Paint ──────────────────────────────────────────────────────────────────

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

    // ── 내부 상태 ──────────────────────────────────────────────────────────────

    private var targetBox: RectF?       = null
    private var composition: Composition? = null
    private var matchState: MatchState  = MatchState.IDLE

    /** 이미지 좌표계 기준 너비 */
    private val MODEL_W = 480f
    /** 이미지 좌표계 기준 높이 */
    private val MODEL_H = 640f

    /** MATCHED 상태 펄스 애니메이터 */
    private var pulseAlpha = 1f
    private val pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
        duration = 700L; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator()
        addUpdateListener { pulseAlpha = it.animatedValue as Float; invalidate() }
    }

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 가이드 박스와 구도를 설정하고 IDLE 상태로 View를 표시한다.
     *
     * @param box         가이드 박스 (480×640 픽셀 좌표계)
     * @param composition 선택된 구도 유형
     */
    fun setGuide(box: RectF, composition: Composition) {
        this.targetBox   = box
        this.composition = composition
        setMatchState(MatchState.IDLE)
    }

    /**
     * 매칭 상태를 유지한 채 가이드 박스 위치·크기만 갱신한다.
     *
     * [기여] MATCHED/RECOMMEND 상태 보존 버그 수정.
     * setGuide()는 항상 IDLE로 리셋하므로, 추적 모드의 프레임 주기 갱신에는
     * 이 메서드를 사용해야 타이머가 초기화되지 않는다.
     *
     * @param box         새 가이드 박스 (480×640 픽셀 좌표계)
     * @param composition 선택된 구도 유형
     */
    fun updateGuideBox(box: RectF, composition: Composition) {
        this.targetBox   = box
        this.composition = composition
        invalidate()   // matchState 는 변경하지 않는다
    }

    /**
     * 매칭 상태를 업데이트한다. MainActivity의 타이머가 상태를 제어한다.
     * MATCHED 상태에만 펄스 애니메이션을 실행한다.
     */
    fun setMatchState(state: MatchState) {
        matchState = state
        if (state == MatchState.MATCHED) {
            if (!pulseAnimator.isRunning) pulseAnimator.start()
        } else {
            pulseAnimator.cancel(); pulseAlpha = 1f
        }
        invalidate()
    }

    /** 가이드 박스를 초기화하고 View를 숨긴다. */
    fun clearGuide() {
        targetBox = null; composition = null
        pulseAnimator.cancel(); pulseAlpha = 1f
        invalidate()
    }

    // ── 가시성 ────────────────────────────────────────────────────────────────

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) pulseAnimator.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); pulseAnimator.cancel()
    }

    // ── 그리기 ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box  = targetBox  ?: return
        val comp = composition ?: return

        // ── 색상 및 알파 결정 ─────────────────────────────────────────────────
        val color = when (matchState) {
            MatchState.IDLE      -> COLOR_IDLE
            MatchState.MATCHED   -> COLOR_MATCHED
            MatchState.RECOMMEND -> COLOR_RECOMMEND
        }
        val alpha = if (matchState == MatchState.MATCHED) (pulseAlpha * 255).toInt() else 255

        // ── 가이드 박스 화면 좌표 변환 (단순 비율, fillCenter 오프셋 없음) ────
        val screenBox = box.toScreen()

        // ── 박스 내부 채우기 ──────────────────────────────────────────────────
        fillPaint.color = color; fillPaint.alpha = (alpha * 0.12f).toInt()
        canvas.drawRect(screenBox, fillPaint)

        // ── 박스 테두리 ───────────────────────────────────────────────────────
        borderPaint.color = color; borderPaint.alpha = alpha
        borderPaint.pathEffect = if (matchState == MatchState.RECOMMEND) null
                                 else DashPathEffect(floatArrayOf(20f, 10f), 0f)
        canvas.drawRect(screenBox, borderPaint)

        // ── L자형 코너 마커 ───────────────────────────────────────────────────
        val cornerPaint = Paint(borderPaint).apply { pathEffect = null; strokeWidth = 8f }
        drawCorners(canvas, screenBox, 30f, cornerPaint)

        // ── 구도 교차점 마커 (원 + 십자) ─────────────────────────────────────
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

        // ── RECOMMEND: "지금 촬영하세요!" 라벨 ───────────────────────────────
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
        canvas.drawLine(box.left,  box.top,    box.left + len, box.top,          paint)
        canvas.drawLine(box.left,  box.top,    box.left,       box.top + len,    paint)
        canvas.drawLine(box.right, box.top,    box.right - len, box.top,         paint)
        canvas.drawLine(box.right, box.top,    box.right,      box.top + len,    paint)
        canvas.drawLine(box.left,  box.bottom, box.left + len, box.bottom,       paint)
        canvas.drawLine(box.left,  box.bottom, box.left,       box.bottom - len, paint)
        canvas.drawLine(box.right, box.bottom, box.right - len, box.bottom,      paint)
        canvas.drawLine(box.right, box.bottom, box.right,      box.bottom - len, paint)
    }

    /** 480×640 이미지 좌표 → View 화면 좌표 변환 */
    private fun RectF.toScreen() = RectF(
        left   / MODEL_W * width,
        top    / MODEL_H * height,
        right  / MODEL_W * width,
        bottom / MODEL_H * height
    )
}
