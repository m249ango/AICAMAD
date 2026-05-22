package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 중력 센서 데이터를 기반으로 카메라 좌우 기울기(roll)를 실시간으로 시각화하는 수준기 뷰.
 *
 * ## 시각 구성
 * - 중앙 점(●)과 좌우로 뻗는 수평선이 기울기 각도만큼 **회전**하여
 *   실제 기울어진 방향을 직관적으로 나타낸다.
 * - 뷰 하단에 현재 기울기 절댓값(°)을 숫자로 표시한다.
 * - 반투명 배경 캡슐로 뷰파인더 위에서도 가독성을 보장한다.
 *
 * ## 수평 기준점
 * `atan2(gx, -gy)` 기준으로 수평 위치는 **0°와 ±180° 두 곳**이다.
 * - 0°: 기기를 한 방향으로 직립했을 때
 * - ±180°: 기기를 반대 방향으로 직립했을 때 (이 앱의 일반적인 촬영 자세)
 *
 * 색상 판정과 각도 표시는 두 기준점 중 **가까운 쪽까지의 편차**([distanceToLevel])를 사용한다.
 * 인디케이터 시각 회전도 raw 각도가 아닌 편차([visualAngle])로 회전하므로,
 * 0°와 180° 모두 수평 상태에서 인디케이터가 수평으로 표시된다.
 *
 * ## 색상 기준 (두 기준점으로부터의 편차 기준)
 * | 편차      | 색상           | 의미          |
 * |-----------|---------------|---------------|
 * | 0° ~ 3°   | 초록 #69F0AE  | 수평 (합격)    |
 * | 3° ~ 5°   | 노랑 #FFD740  | 경미한 기울기  |
 * | 5° 초과   | 빨강 #FF5252  | 심한 기울기    |
 *
 * ## 사용법
 * [MainActivity.onSensorChanged]에서 계산된 roll 각도를 [update]로 전달한다.
 * [update]는 값이 변한 경우에만 [invalidate]를 호출하므로 불필요한 재드로우가 없다.
 */
class LevelIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 현재 표시 중인 roll 각도 (도). 양수 = 시계 방향(오른쪽 기울어짐). */
    private var angleDeg: Float = 0f

    /**
     * 화면 밀도 (dp → px 변환 계수).
     * 모든 크기 상수에 곱하여 다양한 화면 밀도에서 동일한 물리적 크기를 유지한다.
     */
    private val dp = context.resources.displayMetrics.density

    // ── 페인트 ────────────────────────────────────────────────────────────────

    /** 수평선 페인트. 색상은 [levelColor]에서 동적으로 갱신된다. */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * dp
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }

    /** 중앙 점 페인트. 색상은 [levelColor]에서 동적으로 갱신된다. */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /**
     * 반투명 배경 캡슐 페인트.
     * alpha = 140 (255의 약 55%): 뷰파인더 영상이 배경으로 비치면서도
     * 수준기 인디케이터가 충분히 구분될 수 있는 불투명도로 선택했다.
     * 값을 키우면 배경이 짙어져 가시성은 높아지지만 뷰파인더를 더 많이 가린다.
     */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** 각도 숫자 텍스트 페인트. 모노스페이스로 숫자 폭이 고정되어 흔들리지 않는다. */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 11f * dp
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.MONOSPACE
    }

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /**
     * 새 roll 각도를 설정하고 뷰를 다시 그린다.
     * 값이 변하지 않으면 [invalidate]를 호출하지 않아 불필요한 드로우를 방지한다.
     *
     * @param angle roll 각도 (도). 양수 = 시계 방향(오른쪽으로 기울어짐).
     */
    fun update(angle: Float) {
        if (angleDeg != angle) {
            angleDeg = angle
            invalidate()
        }
    }

    // ── 드로우 ────────────────────────────────────────────────────────────────

    /**
     * 수준기 인디케이터를 그린다. [invalidate] 호출마다 실행된다.
     *
     * ## 드로우 순서
     * 1. 반투명 배경 캡슐 (항상 정방향)
     * 2. 회전된 인디케이터 — [canvas.save]/[canvas.rotate]/[canvas.restore] 블록
     *    - 회전량: raw 각도가 아닌 [visualAngle] (가장 가까운 수평 기준점으로부터의 편차)
     *    - 수평선: cx ± [lineHalfLen] 범위
     *    - 중앙 점: 반지름 5 dp
     * 3. 각도 텍스트 (항상 정방향, 뷰 하단) — [distanceToLevel] 값을 표시
     *
     * ## 좌표 기준
     * - [cx]: 뷰 수평 중앙
     * - [cy]: 뷰 높이 52% (상단 52%, 하단 48%) — 텍스트 공간 확보를 위해 중앙보다 약간 위
     */
    override fun onDraw(canvas: Canvas) {
        val cx          = width  / 2f
        // 인디케이터 회전 중심: 뷰 높이 52% 지점.
        // 정중앙(50%)보다 약간 위에 두어 하단 텍스트 영역과 겹치지 않게 한다.
        val cy          = height * 0.52f
        // 선 반길이 40 dp: 120 dp 뷰 너비에서 양쪽 각 10 dp 여백을 남긴 값.
        // 너무 짧으면 기울기 방향이 불명확하고, 너무 길면 배경 캡슐을 벗어난다.
        val lineHalfLen = 40f * dp
        // 배경 캡슐 모서리 반지름 14 dp: Material Design 권장 소형 컴포넌트 반지름(12~16 dp) 중간값.
        val cornerR     = 14f * dp

        val color = levelColor(angleDeg)
        linePaint.color = color
        dotPaint.color  = color

        // 반투명 배경 캡슐
        canvas.drawRoundRect(
            cx - lineHalfLen - 10f * dp,  2f * dp,
            cx + lineHalfLen + 10f * dp,  height.toFloat() - 2f * dp,
            cornerR, cornerR,
            bgPaint
        )

        // 인디케이터를 가장 가까운 수평 기준점(0° 또는 ±180°)으로부터의 편차만큼 회전한다.
        // raw 각도(angleDeg)를 그대로 사용하면 180° 부근에서 인디케이터가 뒤집혀 보이므로,
        // visualAngle()로 변환한 편차를 사용한다.
        // canvas.rotate()로 cx, cy 기준 회전하면 별도 삼각함수 계산 없이 직관적이다.
        canvas.save()
        canvas.rotate(visualAngle(angleDeg), cx, cy)
        canvas.drawLine(cx - lineHalfLen, cy, cx + lineHalfLen, cy, linePaint)
        canvas.drawCircle(cx, cy, 5f * dp, dotPaint)
        canvas.restore()

        // 각도 텍스트: 가장 가까운 수평 기준점까지의 편차(distanceToLevel)를 표시한다.
        // raw 각도 대신 편차를 표시하므로 178°일 때 "178.0°"가 아닌 "2.0°"로 나타난다.
        // 0.05° 미만은 "0.0°"로 고정하여 미세 진동에 의한 숫자 흔들림을 방지한다.
        val dist  = distanceToLevel(angleDeg)
        val label = if (dist < 0.05f) "0.0°" else "%.1f°".format(dist)
        canvas.drawText(label, cx, height.toFloat() - 4f * dp, textPaint)
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    /**
     * 가장 가까운 수평 기준점(0° 또는 ±180°)까지의 편차를 반환한다.
     *
     * atan2 결과상 0°와 ±180° 두 지점이 모두 기기 직립 상태에 해당하므로,
     * 두 기준점 중 더 가까운 쪽까지의 거리를 색상 판정과 텍스트 표시에 사용한다.
     *
     * 예시:
     * - angle =   0° → min(  0, 180) = 0°
     * - angle =   3° → min(  3, 177) = 3°
     * - angle = 178° → min(178,   2) = 2°
     * - angle = 180° → min(180,   0) = 0°
     * - angle = -180° → abs(-180)=180 → min(180, 0) = 0°
     *
     * @param angle roll 각도 (도)
     * @return 0° 이상의 편차 값
     */
    private fun distanceToLevel(angle: Float): Float {
        val a = abs(angle)
        return minOf(a, abs(a - 180f))
    }

    /**
     * 가장 가까운 수평 기준점으로부터의 **부호 있는 편차**를 반환한다.
     * [onDraw]에서 인디케이터 회전량으로 사용한다.
     *
     * 0° 기준일 때는 angle 그대로, ±180° 기준일 때는 ±180°를 빼서
     * 항상 [-90°, +90°] 범위의 회전량을 반환한다.
     *
     * 예시:
     * - angle =   3° (0° 기준) →  3° (오른쪽 기울기)
     * - angle = 177° (180° 기준) → 177 - 180 = -3° (왼쪽 기울기)
     * - angle = -177° (−180° 기준) → -177 + 180 = 3° (오른쪽 기울기)
     *
     * @param angle roll 각도 (도)
     * @return 시각 회전 편차 (도). 양수 = 시계 방향.
     */
    private fun visualAngle(angle: Float): Float = when {
        abs(angle) <= 90f -> angle           // 0° 기준: 편차 = angle 그대로
        angle >= 0f       -> angle - 180f    // 180° 기준: 편차 = angle − 180
        else              -> angle + 180f    // −180° 기준: 편차 = angle + 180
    }

    /**
     * [distanceToLevel] 기반으로 색상을 반환한다.
     *
     * ## 임계값
     * - 3°: 손으로 잡았을 때 허용할 수 있는 최대 오차로 설정 (사용자 피드백 기반)
     * - 5°: 사진에서 기울기가 육안으로 인식되기 시작하는 수준
     *
     * @param angle roll 각도 (도)
     * @return ARGB 색상값
     */
    private fun levelColor(angle: Float): Int = when {
        distanceToLevel(angle) <= 3f -> Color.parseColor("#69F0AE")  // 초록: 수평 (±3° 이내)
        distanceToLevel(angle) <= 5f -> Color.parseColor("#FFD740")  // 노랑: 경미한 기울기
        else                         -> Color.parseColor("#FF5252")  // 빨강: 심한 기울기
    }
}
