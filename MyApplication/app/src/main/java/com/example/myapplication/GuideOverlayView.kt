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
 * 구도 가이드 박스와 교차점 마커를 카메라 프리뷰 위에 오버레이하는 커스텀 View.
 *
 * ## 좌표계
 * 입력값은 480×640 이미지 픽셀 좌표계.
 * View 크기가 3:4 비율로 고정되어 있으므로 단순 비율 변환만 적용한다:
 *   screenX = imageX / 480 * viewWidth
 *   screenY = imageY / 640 * viewHeight
 *
 * ## 상태별 시각 표현
 * | 상태       | 박스 색상     | 선 스타일          | 추가 요소                  |
 * |-----------|-------------|------------------|--------------------------|
 * | IDLE      | 흰색          | 점선              | 없음                      |
 * | MATCHED   | 초록 #00E676  | 점선 + 펄스 애니메이션 | 없음                     |
 * | RECOMMEND | 밝은 초록 #69F0AE | 실선           | "지금 촬영하세요!" 라벨     |
 *
 * ## 사용 흐름
 * 1. [setGuide]: 피사체 선택 후 구도가 결정될 때 최초 1회 호출 → IDLE로 초기화.
 * 2. [updateGuideBox]: 매 [MainActivity.FRAME_UPDATE_INTERVAL] 프레임마다 호출
 *    → 박스 위치만 갱신, matchState 유지 (타이머 초기화 방지).
 * 3. [setMatchState]: MainActivity의 2초 타이머가 IDLE → MATCHED → RECOMMEND 순서로 전환.
 * 4. [clearGuide]: 포커스 해제 시 호출 → 박스 숨김, 애니메이션 종료.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 상태별 색상 ────────────────────────────────────────────────────────────

    /**
     * IDLE 상태 색상 — 흰색.
     * 카메라 프리뷰 배경과 대비가 좋아 가이드 박스가 잘 보이면서도 중립적인 색상이다.
     */
    private val COLOR_IDLE      = Color.WHITE

    /**
     * MATCHED 상태 색상 — 초록 (#00E676).
     * "피사체가 구도 안에 진입했다"는 긍정 신호를 직관적으로 전달한다.
     */
    private val COLOR_MATCHED   = Color.parseColor("#FF00E676")

    /**
     * RECOMMEND 상태 색상 — 밝은 초록 (#69F0AE).
     * MATCHED보다 밝아서 "지금 촬영 가능" 상태임을 한눈에 구분할 수 있다.
     */
    private val COLOR_RECOMMEND = Color.parseColor("#FF69F0AE")

    // ── Paint ──────────────────────────────────────────────────────────────────

    /**
     * 가이드 박스 테두리 Paint.
     * strokeWidth = 6f: OverlayView 기본 박스(8f)보다 얇게 설정하여
     * 가이드 박스가 피사체 박스보다 덜 눈에 띄도록 구분한다.
     * 색상과 DashPathEffect는 상태에 따라 [onDraw]에서 동적으로 변경된다.
     */
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }

    /**
     * 박스 내부 반투명 채우기 Paint.
     * alpha는 [onDraw]에서 `color.alpha × 0.12`로 설정하여 항상 매우 연하게 유지한다.
     * 너무 진하면 카메라 프리뷰를 가려 피사체가 보이지 않기 때문이다.
     */
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }

    /**
     * 구도 교차점 원형 마커 Paint.
     * 교차점에 원을 그려 "피사체 중심을 여기에 맞추세요"라는 시각적 힌트를 준다.
     */
    private val markerCirclePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    /**
     * 구도 교차점 십자 마커 Paint.
     * 원과 겹쳐서 그려 더 정확한 위치를 가리킨다.
     * strokeWidth = 3f: 박스 테두리보다 얇게 설정해 마커가 주객이 되지 않도록 한다.
     */
    private val markerCrossPaint  = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }

    /**
     * "지금 촬영하세요!" 라벨 배경 Paint.
     * alpha = CC (80%): 반투명 검정 배경으로 라벨 텍스트 가독성을 보장한다.
     */
    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000"); style = Paint.Style.FILL
    }

    /**
     * "지금 촬영하세요!" 라벨 텍스트 Paint.
     * textSize = 42f: 프리뷰 위에서 빠르게 인식할 수 있는 최소 크기.
     * isFakeBoldText: 외부 폰트 없이 굵기 강조.
     */
    private val labelTextPaint = Paint().apply {
        textSize = 42f; isAntiAlias = true; isFakeBoldText = true
    }

    // ── 내부 상태 ──────────────────────────────────────────────────────────────

    /**
     * 현재 표시 중인 가이드 박스 (480×640 이미지 좌표계).
     * null이면 [onDraw]에서 아무것도 그리지 않는다.
     */
    private var targetBox: RectF?       = null

    /** 현재 선택된 구도 유형. 교차점 마커 위치 계산에 사용한다. */
    private var composition: Composition? = null

    /** 현재 매칭 상태. 색상·선 스타일·애니메이션을 결정한다. */
    private var matchState: MatchState  = MatchState.IDLE

    /** 이미지 좌표계 기준 너비 (480 px) */
    private val MODEL_W = 480f
    /** 이미지 좌표계 기준 높이 (640 px) */
    private val MODEL_H = 640f

    /**
     * MATCHED 상태에서 박스 테두리 알파를 0.4↔1.0으로 왕복하는 펄스 애니메이터.
     *
     * duration = 700ms: 너무 빠르면 산만하고, 너무 느리면 반응이 없어 보인다.
     * 700ms는 눈에 부드럽게 인식되면서 "진행 중" 상태를 충분히 강조한다.
     * repeatMode = REVERSE: 별도의 역방향 키프레임 없이 자동 왕복.
     * LinearInterpolator: 일정한 속도로 변화하여 기계적 느낌을 준다
     * (EaseInOut보다 리듬감이 명확하다).
     */
    private var pulseAlpha = 1f
    private val pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
        duration = 700L; repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator()
        addUpdateListener { pulseAlpha = it.animatedValue as Float; invalidate() }
    }

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 가이드 박스와 구도를 설정하고 IDLE 상태로 View를 초기화한다.
     *
     * 피사체 선택 후 구도가 처음 결정될 때 호출한다.
     * matchState를 항상 IDLE로 리셋하므로, 추적 중 박스만 갱신할 때는
     * [updateGuideBox]를 사용해야 타이머가 초기화되지 않는다.
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
     * 매칭 상태를 유지한 채 가이드 박스의 위치·크기만 갱신한다.
     *
     * ## [setGuide]와의 차이
     * [setGuide]는 항상 IDLE로 리셋한다. 반면 이 메서드는 matchState를 건드리지 않아
     * MATCHED 타이머나 RECOMMEND 상태가 보존된다.
     *
     * ## 왜 분리가 필요한가?
     * MainActivity는 [FRAME_UPDATE_INTERVAL] 프레임마다 피사체 박스에 맞게 가이드 박스를
     * 재계산한다. 이때 [setGuide]를 쓰면 매 N 프레임마다 IDLE로 리셋되어
     * 2초 타이머가 절대 충족되지 않는다. 이 메서드로 박스만 갱신해야 타이머가 유지된다.
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
     * 매칭 상태를 변경하고 뷰를 다시 그린다.
     *
     * MATCHED 상태에 진입하면 펄스 애니메이션을 시작하고,
     * 다른 상태로 전환되면 애니메이션을 중단하여 알파를 1.0으로 복원한다.
     *
     * MainActivity의 타이머가 IDLE → MATCHED → RECOMMEND 순으로 이 메서드를 호출한다.
     *
     * @param state 새 매칭 상태
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

    /**
     * 가이드 박스·구도를 초기화하고 펄스 애니메이션을 종료한다.
     * MainActivity의 [unfocusSubject] 또는 [clearGuide]에서 호출한다.
     * View.GONE 처리는 호출부(MainActivity)에서 담당한다.
     */
    fun clearGuide() {
        targetBox = null; composition = null
        pulseAnimator.cancel(); pulseAlpha = 1f
        invalidate()
    }

    // ── 가시성 / 생명주기 ────────────────────────────────────────────────────────

    /**
     * View가 숨겨질 때 펄스 애니메이션을 중단한다.
     *
     * View가 GONE/INVISIBLE 상태에서도 ValueAnimator가 계속 실행되면
     * 불필요한 CPU 사이클과 배터리를 소모한다.
     * [onDetachedFromWindow]와 함께 이중으로 방어한다.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) pulseAnimator.cancel()
    }

    /**
     * View가 윈도우에서 분리될 때 (액티비티 종료 등) 애니메이터를 반드시 중단한다.
     * 애니메이터를 중단하지 않으면 메모리 릭의 원인이 될 수 있다.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); pulseAnimator.cancel()
    }

    // ── 그리기 ─────────────────────────────────────────────────────────────────

    /**
     * 구도 가이드 요소들을 그린다. [invalidate] 또는 펄스 애니메이터 업데이트마다 실행된다.
     *
     * ## 드로우 순서 (레이어 순서)
     * 1. 박스 내부 채우기 (반투명 — 가장 아래 레이어)
     * 2. 박스 테두리 (점선 또는 실선)
     * 3. L자형 코너 마커 (테두리보다 두꺼운 실선)
     * 4. 구도 교차점 마커 (원 + 십자)
     * 5. RECOMMEND 라벨 ("지금 촬영하세요!" — 가장 위 레이어)
     *
     * [targetBox] 또는 [composition]이 null이면 아무것도 그리지 않고 즉시 반환한다.
     */
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
        // MATCHED 펄스: 0.4~1.0 사이로 변동 / 그 외 상태: 항상 255 (완전 불투명)
        val alpha = if (matchState == MatchState.MATCHED) (pulseAlpha * 255).toInt() else 255

        val screenBox = box.toScreen()

        // ① 박스 내부 채우기 — alpha × 12% 로 매우 연하게
        fillPaint.color = color; fillPaint.alpha = (alpha * 0.12f).toInt()
        canvas.drawRect(screenBox, fillPaint)

        // ② 박스 테두리 — RECOMMEND는 실선, 나머지는 점선(20px dash, 10px gap)
        borderPaint.color = color; borderPaint.alpha = alpha
        borderPaint.pathEffect = if (matchState == MatchState.RECOMMEND) null
                                 else DashPathEffect(floatArrayOf(20f, 10f), 0f)
        canvas.drawRect(screenBox, borderPaint)

        // ③ L자형 코너 마커 — 점선 효과 없는 실선, 테두리보다 두꺼운 8f
        val cornerPaint = Paint(borderPaint).apply { pathEffect = null; strokeWidth = 8f }
        drawCorners(canvas, screenBox, 30f, cornerPaint)

        // ④ 구도 교차점 마커 (원 + 십자) — alpha의 70%로 약간 투명하게
        val markerAlpha = (alpha * 0.7f).toInt()
        markerCirclePaint.color = color; markerCirclePaint.alpha = markerAlpha
        markerCrossPaint.color  = color; markerCrossPaint.alpha  = markerAlpha
        for (pt in CompositionGuideCalculator.getIntersectionPoints(comp)) {
            val sx = pt.x / MODEL_W * width
            val sy = pt.y / MODEL_H * height
            canvas.drawCircle(sx, sy, 8f, markerCirclePaint)
            val arm = 18f  // 십자 한쪽 팔 길이
            canvas.drawLine(sx - arm, sy, sx + arm, sy, markerCrossPaint)
            canvas.drawLine(sx, sy - arm, sx, sy + arm, markerCrossPaint)
        }

        // ⑤ RECOMMEND 전용 라벨
        if (matchState == MatchState.RECOMMEND) drawRecommendLabel(canvas, screenBox, color)
    }

    /**
     * "지금 촬영하세요!" 라벨을 가이드 박스 상단 위에 그린다.
     *
     * 라벨이 화면 상단을 벗어나지 않도록 textY의 최솟값을 `textSize + 14f`로 제한한다.
     * 텍스트 배경은 둥근 모서리(8dp) 반투명 박스로 그려 가독성을 높인다.
     *
     * @param canvas    그릴 캔버스
     * @param box       가이드 박스 화면 좌표 (라벨 위치 기준점)
     * @param color     텍스트 색상 (RECOMMEND 색상과 통일)
     */
    private fun drawRecommendLabel(canvas: Canvas, box: RectF, color: Int) {
        val label = "지금 촬영하세요!"
        val bounds = Rect()
        labelTextPaint.color = color
        labelTextPaint.getTextBounds(label, 0, label.length, bounds)
        val textX = box.left
        // 박스 상단 8px 위에 배치. 상단 여백 부족 시 textSize + 14px 위치로 내린다.
        val textY = maxOf(box.top - 8f, labelTextPaint.textSize + 14f)
        canvas.drawRoundRect(
            RectF(textX, textY - bounds.height() - 12f,
                  textX + bounds.width() + 24f, textY + 10f),
            8f, 8f, labelBgPaint
        )
        canvas.drawText(label, textX + 12f, textY, labelTextPaint)
    }

    /**
     * 사각형의 네 모서리에 L자형 마커를 그린다.
     *
     * 각 코너에서 수평·수직으로 [len] 픽셀씩 선을 그어 총 8개의 짧은 선을 그린다.
     * 박스 테두리 전체를 그리는 것보다 구도 가이드가 카메라 UI처럼 보여
     * 사용자에게 "맞춰야 할 프레임"임을 직관적으로 전달한다.
     *
     * @param canvas 그릴 캔버스
     * @param box    대상 사각형 (화면 좌표)
     * @param len    L자 선 길이 (픽셀)
     * @param paint  선 스타일 Paint
     */
    private fun drawCorners(canvas: Canvas, box: RectF, len: Float, paint: Paint) {
        // 좌상 코너
        canvas.drawLine(box.left,  box.top,    box.left + len, box.top,          paint)
        canvas.drawLine(box.left,  box.top,    box.left,       box.top + len,    paint)
        // 우상 코너
        canvas.drawLine(box.right, box.top,    box.right - len, box.top,         paint)
        canvas.drawLine(box.right, box.top,    box.right,      box.top + len,    paint)
        // 좌하 코너
        canvas.drawLine(box.left,  box.bottom, box.left + len, box.bottom,       paint)
        canvas.drawLine(box.left,  box.bottom, box.left,       box.bottom - len, paint)
        // 우하 코너
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
