package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null

    /**
     * 객체 감지 기능 활성화 여부.
     * true → MediaPipe 실시간 감지 + OverlayView + 구도 가이드 활성.
     * 기본값 ON: 앱 시작 시 객체 감지가 즉시 동작한다.
     */
    private var isObjectDetectionOn = true

    /**
     * 카테고리 분류 기능 활성화 여부.
     * true → LandscapeClassifier 실시간 추론 + 우상단 점수 패널 활성.
     * 기본값 OFF: 사용자가 명시적으로 켜야 동작한다 (모델 로드 비용 고려).
     */
    private var isCategoryClassificationOn = false

    // ── 수준기 센서 필드 ────────────────────────────────────────────────────────

    /**
     * 시스템 센서 서비스. 중력 센서 등록/해제에 사용한다.
     * [onResume]/[onPause]에서 리스너를 등록·해제하여 백그라운드 배터리 소모를 방지한다.
     */
    private lateinit var sensorManager: SensorManager

    /**
     * 중력 센서 (TYPE_GRAVITY).
     * 하드웨어 저역통과 필터가 내장되어 있어 별도 소프트웨어 필터링이 불필요하다.
     * 기기에 따라 null일 수 있으며, 그 경우 [accelerometerSensor]로 대체한다.
     */
    private var gravitySensor: Sensor? = null

    /**
     * 가속도 센서 (TYPE_ACCELEROMETER).
     * [gravitySensor]가 없는 기기에서의 폴백.
     * 진동 잡음이 있으므로 [onSensorChanged]에서 [LP_ALPHA]로 저역통과 필터를 적용한다.
     */
    private var accelerometerSensor: Sensor? = null

    /**
     * 저역통과 필터 상태 — X축 중력 성분.
     * [accelerometerSensor] 폴백 시 진동 잡음 제거를 위해 사용한다.
     * 초기값 0f: 직립 상태에서 X축 중력 성분은 0에 가깝다.
     */
    private var lpGx = 0f

    /**
     * 저역통과 필터 상태 — Y축 중력 성분.
     * 초기값 -9.8f: 직립 portrait 상태에서 Y축은 위를 향하므로 중력은 -9.8f.
     * 이 초기값 덕분에 첫 프레임에서 이상한 각도가 표시되지 않는다.
     */
    private var lpGy = -9.8f

    /**
     * CameraX 정지 사진 캡처 유즈케이스.
     * [startCamera]에서 [ProcessCameraProvider]에 바인딩되며,
     * [takePhoto]에서 임시 파일로 JPEG를 저장하는 데 사용한다.
     * 카메라가 아직 초기화되지 않은 경우 null이므로 [takePhoto]에서 null 체크 후 사용한다.
     */
    private var imageCapture: ImageCapture? = null

    // ── 풍경 모드 필드 ────────────────────────────────────────────────────────

    /**
     * 풍경 구도 분류기. 풍경 모드 첫 진입 시 백그라운드에서 초기화된다.
     * 모델 파일(~수십 MB)을 내부 저장소로 복사하는 시간이 필요하므로 지연 로딩한다.
     */
    private var landscapeClassifier: LandscapeClassifier? = null

    /**
     * 풍경 모드 추론 중복 실행 방지 플래그.
     * 추론 완료 전에 다음 프레임이 도착해도 새 추론을 시작하지 않는다.
     */
    private val isInferenceRunning = AtomicBoolean(false)

    // ── 구도 모드 상태 필드 ─────────────────────────────────────────────────────

    /**
     * 사용자가 터치로 선택한 피사체의 카테고리 라벨.
     * 다음 프레임에서 같은 라벨을 가진 박스를 찾아 피사체를 추적한다.
     * null이면 피사체 미선택(일반 모드).
     */
    private var selectedSubjectLabel: String? = null

    /**
     * 직전 프레임에서 추적한 피사체 중심 좌표 (480×640).
     * [setupCompositionButton]에서 구도 가이드 초기 위치 계산에 사용한다.
     */
    private var lastKnownCenter: PointF? = null

    /**
     * 직전 프레임에서 추적한 피사체 바운딩 박스 (480×640).
     * [findTrackedBox]의 두 가지 필터 모두에 사용된다:
     * 1. 중심 좌표 → 거리 상한 필터 기준점
     * 2. 박스 영역 → IoU 계산 기준 (연속성 판단)
     *
     * [lastKnownCenter]와 항상 동기화하여 갱신된다.
     * null이면 추적 중이 아님.
     */
    private var lastKnownBox: RectF? = null

    /** 사용자가 선택한 구도 유형. null이면 구도 가이드 비활성. */
    private var selectedComposition: Composition? = null

    /**
     * 가이드 박스의 목표 위치 (480×640 이미지 좌표계).
     * [CompositionGuideCalculator.computeTargetBox] 결과를 저장한다.
     * [FRAME_UPDATE_INTERVAL] 프레임마다 재계산된다.
     */
    private var guideBox: RectF? = null

    /**
     * 피사체 중심이 현재 [guideBox] 안에 있는지 여부.
     * true → MATCHED 타이머 진행 중 / false → IDLE 또는 타이머 리셋.
     */
    private var isMatched = false

    /** 2초 연속 매칭 후 RECOMMEND 상태로 전환하는 타이머용 Handler */
    private val matchHandler = Handler(Looper.getMainLooper())

    /**
     * Handler에 등록된 RECOMMEND 전환 Runnable.
     * 피사체가 [guideBox] 밖으로 나가면 이 Runnable을 취소하여 타이머를 리셋한다.
     */
    private var matchRunnable: Runnable? = null

    /** RECOMMEND 상태에 도달했는지 여부. 도달하면 타이머를 재시작하지 않는다. */
    private var isRecommended = false

    /**
     * 감지 결과 수신 프레임 카운터.
     * [FRAME_UPDATE_INTERVAL] 프레임마다 OverlayView의 추적 박스와 guideBox를 갱신하여
     * 박스 크기·위치가 급격히 변동하는 것을 방지한다.
     */
    private var frameCounter = 0

    // ────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupFeatureToggles()
        setupSensor()
        setupShutterButton()
        setupCompositionButton()
        setupUnfocusButton()
        setupOverlayTouchCallback()
        setupGalleryButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    // ── 구도 버튼 설정 ──────────────────────────────────────────────────────────

    /**
     * 구도 선택 버튼 클릭 시 PopupMenu를 띄워 구도 유형을 선택하게 한다.
     *
     * 구도가 선택되면:
     * 1. [selectedComposition] 갱신.
     * 2. 피사체가 이미 선택된 상태라면 [showGuide]로 가이드 박스를 즉시 생성한다.
     */
    private fun setupCompositionButton() {
        binding.btnComposition.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            Composition.values().forEachIndexed { i, comp ->
                menu.menu.add(0, i, i, comp.displayName)
            }
            menu.setOnMenuItemClickListener { item ->
                val comp = Composition.values()[item.itemId]
                selectedComposition = comp
                Toast.makeText(this, "${comp.displayName} 구도 선택됨", Toast.LENGTH_SHORT).show()

                // 피사체가 이미 선택된 상태라면 가이드 박스를 즉시 생성한다.
                val center = lastKnownCenter
                if (center != null) {
                    // 현재 lastKnownCenter를 기반으로 임시 박스 생성 — 다음 프레임에 정확히 갱신됨
                    val halfW = 80f; val halfH = 100f  // 초기 근사 크기
                    val approxBox = RectF(
                        center.x - halfW, center.y - halfH,
                        center.x + halfW, center.y + halfH
                    )
                    showGuide(approxBox, comp)
                }
                true
            }
            menu.show()
        }
    }

    // ── 포커스 해제 버튼 설정 ───────────────────────────────────────────────────

    /**
     * 포커스 해제 버튼 클릭 시 피사체 선택 및 구도 가이드를 모두 초기화한다.
     */
    private fun setupUnfocusButton() {
        binding.btnUnfocus.setOnClickListener {
            unfocusSubject()
        }
    }

    /** 갤러리 열기 버튼 클릭 → GalleryActivity 실행 */
    private fun setupGalleryButton() {
        binding.btnOpenGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    // ── 수준기 센서 설정 ─────────────────────────────────────────────────────

    /**
     * 중력 센서(또는 가속도 센서 폴백)를 초기화한다.
     *
     * TYPE_GRAVITY는 하드웨어 저역통과 필터가 내장되어 있어 잡음이 적고 별도 처리가 불필요하다.
     * 지원하지 않는 기기에서는 TYPE_ACCELEROMETER를 사용하고,
     * [onSensorChanged]에서 소프트웨어 저역통과 필터([LP_ALPHA])를 적용하여 잡음을 줄인다.
     */
    private fun setupSensor() {
        sensorManager     = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor     = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Log.d("MainActivity", "TYPE_GRAVITY 없음 — TYPE_ACCELEROMETER 폴백 사용")
        }
    }

    /**
     * 포그라운드 진입 시 센서 리스너를 등록한다.
     * SENSOR_DELAY_UI (~60 ms 간격)로 수준기에 충분한 응답성을 유지하면서
     * 불필요한 연산을 줄인다.
     */
    override fun onResume() {
        super.onResume()
        val sensor = gravitySensor ?: accelerometerSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /**
     * 백그라운드 전환 시 센서 리스너를 즉시 해제하여 배터리 소모를 방지한다.
     */
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    /**
     * 중력(또는 가속도) 센서 값 갱신 시 roll 각도를 계산하여 수준기 뷰에 전달한다.
     *
     * ## roll 각도 계산 원리 (세로 portrait 기준)
     * Android 센서 좌표계:
     * - X축: 기기 오른쪽 방향
     * - Y축: 기기 위쪽 방향
     * - 중력 벡터: 지구 중심 방향 (아래)
     *
     * 완전 직립 시: gx ≈ 0, gy ≈ -9.8
     * `atan2(gx, -gy)` = `atan2(0, 9.8)` ≈ 0°  → 수평
     * 오른쪽으로 θ 기울면: gx > 0 → atan2 > 0 → 양수 각도 (시계 방향)
     *
     * TYPE_ACCELEROMETER 폴백 시 저역통과 필터를 적용한다:
     *   filtered = α × raw + (1 − α) × filtered_prev
     * α = [LP_ALPHA] = 0.15 → 약 380 ms 평활화 (손 떨림 제거에 적합).
     */
    override fun onSensorChanged(event: SensorEvent) {
        val gx: Float
        val gy: Float

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                // TYPE_GRAVITY: 하드웨어 필터 내장 → 직접 사용
                gx = event.values[0]
                gy = event.values[1]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // TYPE_ACCELEROMETER 폴백: 소프트웨어 저역통과 필터로 잡음 제거
                lpGx = LP_ALPHA * event.values[0] + (1f - LP_ALPHA) * lpGx
                lpGy = LP_ALPHA * event.values[1] + (1f - LP_ALPHA) * lpGy
                gx = lpGx
                gy = lpGy
            }
            else -> return
        }

        // 세로 모드 기준 좌우 기울기 각도 (도 단위)
        val roll = Math.toDegrees(atan2(gx.toDouble(), (-gy).toDouble())).toFloat()
        binding.levelIndicator.update(roll)
    }

    /** 센서 정확도 변화는 수준기 판단에 영향 없으므로 무시한다. */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 피사체 포커스를 해제하고 일반 모드로 복귀한다.
     *
     * - OverlayView를 전체 박스 표시 모드로 전환
     * - 가이드 박스 및 매칭 타이머 초기화
     * - compositionBar 숨김
     * - 모든 선택 상태 필드 초기화
     */
    private fun unfocusSubject() {
        selectedSubjectLabel = null
        lastKnownCenter      = null
        lastKnownBox         = null
        selectedComposition  = null
        frameCounter         = 0

        binding.overlayView.clearSelection()
        clearGuide()
        binding.compositionBar.visibility = View.GONE
    }

    // ── OverlayView 터치 콜백 설정 ────────────────────────────────────────────

    /**
     * OverlayView의 터치 콜백 두 가지를 등록한다.
     *
     * - [OverlayView.onDetectionSelected]: 터치 지점에 객체 1개 → [selectSubject]로 즉시 선택.
     * - [OverlayView.onMultipleDetectionsFound]: 터치 지점에 객체 2개 이상 → [showCandidatePopup]
     *   으로 사용자에게 선택을 위임한다.
     */
    private fun setupOverlayTouchCallback() {
        // 단일 객체 터치 — 즉시 선택
        binding.overlayView.onDetectionSelected = { index, box, label ->
            selectSubject(index, box, label)
        }

        // 복수 객체 중첩 터치 — 팝업으로 선택 위임
        binding.overlayView.onMultipleDetectionsFound = { candidates, touchX, touchY ->
            showCandidatePopup(candidates, touchX, touchY)
        }
    }

    /**
     * 피사체를 선택하고 추적 모드를 시작한다.
     *
     * 단일 터치([OverlayView.onDetectionSelected])와 팝업 선택([showCandidatePopup]) 모두
     * 이 함수를 통해 동일한 선택 로직을 실행하도록 공통화한다.
     *
     * @param index OverlayView.selectedIndex 에 설정할 감지 결과 인덱스
     * @param box   피사체 바운딩 박스 (480×640 이미지 좌표계)
     * @param label 카테고리 라벨
     */
    private fun selectSubject(index: Int, box: RectF, label: String) {
        selectedSubjectLabel = label
        lastKnownCenter      = PointF(box.centerX(), box.centerY())
        lastKnownBox         = RectF(box)
        frameCounter         = 0

        // OverlayView → 추적 모드: 선택된 박스만 표시
        binding.overlayView.selectedIndex = index
        binding.overlayView.setTrackedBox(box, label)

        // compositionBar 노출 (구도 선택 + 포커스 해제 버튼)
        binding.compositionBar.visibility = View.VISIBLE

        // 구도가 이미 선택된 상태라면 가이드 즉시 갱신
        val comp = selectedComposition
        if (comp != null) showGuide(box, comp) else clearGuide()
    }

    /**
     * 중첩된 감지 후보 목록을 [ListPopupWindow]로 표시하여 사용자가 원하는 객체를 선택하게 한다.
     *
     * 팝업은 터치 지점 근처에 나타나며, 각 항목은 "라벨명  신뢰도%" 형식으로 표시된다.
     * 항목 선택 시 [selectSubject]를 호출하여 해당 피사체를 추적 모드로 전환한다.
     * 팝업 외부를 터치하면 자동으로 닫힌다.
     *
     * @param candidates 터치 지점에 중첩된 감지 후보 목록
     * @param touchX     OverlayView 내 터치 X 좌표 (팝업 수평 위치 계산용)
     * @param touchY     OverlayView 내 터치 Y 좌표 (팝업 수직 위치 계산용)
     */
    private fun showCandidatePopup(
        candidates: List<DetectionCandidate>,
        touchX: Float,
        touchY: Float
    ) {
        // 팝업 항목 문자열: "고양이  87%" 형식
        val displayItems = candidates.map { "${it.label}  ${it.score}%" }

        val popup = ListPopupWindow(this)
        popup.anchorView = binding.overlayView
        popup.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems)
        )

        // 팝업 너비: 항목이 잘리지 않도록 200dp 고정
        popup.width = (200 * resources.displayMetrics.density).toInt()

        // 팝업 위치: 터치 지점 기준
        // horizontalOffset — anchorView 좌측 끝에서의 수평 오프셋
        // verticalOffset   — anchorView 하단 끝에서의 수직 오프셋 (음수 = 위 방향)
        // → touchY - overlayView.height 로 설정하면 팝업 상단이 터치 Y에 정렬된다
        popup.horizontalOffset = touchX.toInt()
        popup.verticalOffset   = touchY.toInt() - binding.overlayView.height

        // 팝업 외부 터치 시 자동 닫힘
        popup.isModal = true

        popup.setOnItemClickListener { _, _, position, _ ->
            val selected = candidates[position]
            selectSubject(selected.index, selected.box, selected.label)
            popup.dismiss()
        }

        popup.show()
    }

    // ── 가이드 관리 ─────────────────────────────────────────────────────────────

    /**
     * 피사체 박스와 구도를 받아 [guideBox]를 계산하고 GuideOverlayView를 IDLE 상태로 표시한다.
     * 매칭 타이머도 초기화된다.
     */
    private fun showGuide(subjectBox: RectF, comp: Composition) {
        val newGuide = CompositionGuideCalculator.computeTargetBox(subjectBox, comp)
        guideBox      = newGuide
        isRecommended = false
        resetMatchTimer()

        binding.guideOverlay.visibility = View.VISIBLE
        binding.guideOverlay.setGuide(newGuide, comp)
    }

    /**
     * 가이드 박스를 초기화하고 GuideOverlayView를 숨긴다.
     * 매칭 타이머도 함께 취소한다.
     */
    private fun clearGuide() {
        guideBox      = null
        isRecommended = false
        resetMatchTimer()

        binding.guideOverlay.clearGuide()
        binding.guideOverlay.visibility = View.GONE
    }

    // ── 피사체 추적 및 매칭 상태 관리 ───────────────────────────────────────────

    /**
     * 감지 결과에서 추적 중인 피사체 박스를 찾는다.
     *
     * ## 개선된 2단계 필터
     *
     * ### 1단계: 최대 이동 거리 필터 ([MAX_TRACKING_DIST_SQ])
     * 한 프레임에서 피사체가 이동 가능한 최대 거리를 초과한 박스를 후보에서 제외한다.
     * - 범위 밖에 있는 동명 객체로는 절대 전환되지 않는다 ('순간이동' 방지).
     * - 범위 내 후보가 없으면 null 반환 → [lastKnownCenter]/[lastKnownBox] 갱신 없음
     *   (피사체 일시 소실 시 드리프트 방지).
     *
     * ### 2단계: IoU + 거리 복합 점수
     * 범위 내 후보가 복수일 때 [lastKnownBox]와의 IoU(겹침 비율)를 추가로 고려한다.
     * 이전 박스와 형태·위치가 유사한 박스가 동일 피사체일 가능성이 높다.
     *
     * ```
     * score = (1 - IoU) × 0.6 + normDist × 0.4
     * ```
     * - IoU가 높을수록 (겹침 많음 = 연속성 높음) → score 낮음 → 우선 선택
     * - 후보가 1개뿐이면 IoU=0이라도 그 후보를 반환 (겹침 없는 빠른 이동 대응)
     *
     * @return 추적 박스 (480×640 이미지 좌표계), 범위 내 후보 없으면 null
     */
    private fun findTrackedBox(result: ObjectDetectorResult): RectF? {
        val label   = selectedSubjectLabel ?: return null
        val prevBox = lastKnownBox         ?: return null

        val prevCx = prevBox.centerX()
        val prevCy = prevBox.centerY()

        // ① 같은 라벨 중 최대 이동 거리 이내 후보만 선별
        val candidates = result.detections()
            .filter { det -> det.categories().any { it.categoryName() == label } }
            .filter { det ->
                val dx = det.boundingBox().centerX() - prevCx
                val dy = det.boundingBox().centerY() - prevCy
                dx * dx + dy * dy <= MAX_TRACKING_DIST_SQ
            }

        if (candidates.isEmpty()) return null

        // ② 복합 점수 최솟값 후보 선택
        return candidates.minByOrNull { det ->
            val box      = RectF(det.boundingBox())
            val iou      = computeIoU(prevBox, box)
            val dx       = box.centerX() - prevCx
            val dy       = box.centerY() - prevCy
            val normDist = (dx * dx + dy * dy) / MAX_TRACKING_DIST_SQ
            // IoU 가중치 0.6: 인접 객체 구분이 핵심 목적이므로 거리보다 높게 설정
            (1f - iou) * 0.6f + normDist * 0.4f
        }?.let { RectF(it.boundingBox()) }
    }

    /**
     * 두 직사각형 박스의 IoU (Intersection over Union)를 계산한다.
     *
     * IoU = 교집합 넓이 / 합집합 넓이
     * - 완전 일치: 1.0 / 전혀 겹치지 않음: 0.0
     *
     * [findTrackedBox]에서 연속 프레임 간 박스 동일성 판단에 사용한다.
     * 같은 피사체는 프레임 간 높은 IoU를, 다른 객체는 낮은 IoU를 가진다.
     *
     * @param a 이전 프레임 박스
     * @param b 현재 프레임 박스 후보
     * @return IoU 값 [0.0, 1.0]
     */
    private fun computeIoU(a: RectF, b: RectF): Float {
        val interL = maxOf(a.left,   b.left)
        val interT = maxOf(a.top,    b.top)
        val interR = minOf(a.right,  b.right)
        val interB = minOf(a.bottom, b.bottom)

        val interW = maxOf(0f, interR - interL)
        val interH = maxOf(0f, interB - interT)
        val interArea = interW * interH
        if (interArea == 0f) return 0f

        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        return interArea / (aArea + bArea - interArea)
    }

    /**
     * 피사체 중심 좌표를 받아 [guideBox]와의 매칭 상태를 판단하고 UI를 갱신한다.
     *
     * 허용 반경은 **가이드 박스 절반 크기와 [MATCH_MAX_HALF_X]/[MATCH_MAX_HALF_Y] 중 작은 값**이다.
     * 이 상한 덕분에 피사체가 아무리 커도 허용 범위가 무한히 커지지 않는다.
     *
     * - 허용 반경 이내 → MATCHED 상태 + 2초 타이머 시작
     * - 허용 반경 이탈 → IDLE 복귀 + 타이머 취소 (RECOMMEND 이후엔 유지)
     *
     * @param subjectCenter 현재 프레임의 피사체 중심 (480×640 이미지 좌표계)
     */
    private fun checkMatchState(subjectCenter: PointF) {
        val guide = guideBox ?: return

        val allowHalfX = minOf(guide.width()  / 2f, MATCH_MAX_HALF_X)
        val allowHalfY = minOf(guide.height() / 2f, MATCH_MAX_HALF_Y)

        val dx = subjectCenter.x - guide.centerX()
        val dy = subjectCenter.y - guide.centerY()
        val inMatchZone = dx >= -allowHalfX && dx <= allowHalfX &&
                          dy >= -allowHalfY && dy <= allowHalfY

        if (inMatchZone) {
            if (!isMatched) {
                isMatched = true
                if (!isRecommended) {
                    binding.guideOverlay.setMatchState(MatchState.MATCHED)
                    val runnable = Runnable {
                        isRecommended = true
                        binding.guideOverlay.setMatchState(MatchState.RECOMMEND)
                    }
                    matchRunnable = runnable
                    matchHandler.postDelayed(runnable, MATCH_HOLD_MS)
                }
            }
        } else {
            if (isMatched) {
                isMatched = false
                if (!isRecommended) {
                    resetMatchTimer()
                    binding.guideOverlay.setMatchState(MatchState.IDLE)
                }
            }
        }
    }

    /**
     * 매칭 타이머를 취소하고 [isMatched]를 초기화한다.
     * [guideBox]를 새로 설정하거나 피사체가 이탈할 때 호출한다.
     */
    private fun resetMatchTimer() {
        matchRunnable?.let { matchHandler.removeCallbacks(it) }
        matchRunnable = null
        isMatched     = false
    }

    // ── 기능 토글 설정 ──────────────────────────────────────────────────────────

    /**
     * 객체 감지 토글과 카테고리 분류 토글을 독립적으로 설정한다.
     *
     * 두 토글은 상호 배타적이지 않으며, 동시에 활성화할 수 있다.
     * XML에서 switchObjectMode(checked=true)·switchLandscapeMode(checked=false)로
     * 초기 상태가 지정되어 있으므로 여기서는 리스너만 연결한다.
     *
     * ## 객체 감지 토글 (switchObjectMode)
     * - ON  → OverlayView 표시, MediaPipe 실시간 감지 활성화
     * - OFF → OverlayView 숨김, 피사체 선택·구도 가이드 전체 초기화
     *
     * ## 카테고리 분류 토글 (switchLandscapeMode)
     * - ON  → 분류 상태 패널 표시, LandscapeClassifier 지연 초기화 (최초 1회)
     * - OFF → 분류 상태 패널 숨김, 추론 비활성화 (모델은 메모리에 유지)
     *
     * SwitchCompat은 내부적으로 체크 상태를 관리하므로 별도 알파·아이콘 조작이 불필요하다.
     */
    private fun setupFeatureToggles() {

        // ── 객체 감지 토글 ──────────────────────────────────────────────────────
        // isChecked: 토글이 ON이면 true, OFF이면 false.
        // 사용자가 스위치를 누를 때마다 SwitchCompat이 isChecked를 자동으로 반전시킨다.
        binding.switchObjectMode.setOnCheckedChangeListener { _, isChecked ->
            isObjectDetectionOn = isChecked

            if (isChecked) {
                // 객체 감지 ON → OverlayView 다시 표시
                binding.overlayView.visibility = View.VISIBLE
            } else {
                // 객체 감지 OFF → OverlayView 숨김, 피사체 선택 및 가이드 초기화
                binding.overlayView.visibility = View.GONE
                unfocusSubject()
            }

            Toast.makeText(
                this,
                if (isChecked) "객체 감지 ON" else "객체 감지 OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ── 카테고리 분류 토글 ──────────────────────────────────────────────────
        binding.switchLandscapeMode.setOnCheckedChangeListener { _, isChecked ->
            isCategoryClassificationOn = isChecked

            if (isChecked) {
                // 카테고리 분류 ON → 패널 표시 + LandscapeClassifier 지연 로딩
                binding.landscapeScoreLayout.visibility = View.VISIBLE
                binding.tvCompositionName.text = "분석 중…"
                binding.tvLandscapeScore.text  = "—"

                // 모델 초기화가 아직 안 된 경우에만 백그라운드에서 로드한다.
                // 이미 초기화된 경우 재로드 없이 추론이 즉시 재개된다.
                if (landscapeClassifier == null) {
                    cameraExecutor.execute {
                        landscapeClassifier = LandscapeClassifier(this)
                    }
                }
            } else {
                // 카테고리 분류 OFF → 패널 숨김 (모델은 메모리에 유지하여 재진입 시 빠르게 재개)
                binding.landscapeScoreLayout.visibility = View.GONE
            }

            Toast.makeText(
                this,
                if (isChecked) "카테고리 분류 ON" else "카테고리 분류 OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 셔터 버튼을 동적으로 생성하고 화면 하단 중앙에 배치한다.
     *
     * XML 레이아웃 대신 코드로 생성하는 이유:
     * - 다른 오버레이 View들이 ConstraintLayout 자식으로 이미 배치되어 있고,
     *   셔터 버튼은 항상 최상단(elevation 최고)에 있어야 하므로 마지막에 addView한다.
     * - 버튼 스타일(흰 원형, 회색 테두리)을 코드로 직접 정의하여
     *   별도 drawable XML 파일 없이 관리한다.
     */
    private fun setupShutterButton() {
        val shutterDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(12, Color.parseColor("#DDDDDD"))
        }

        val captureButton = Button(this).apply {
            id = View.generateViewId()
            background = shutterDrawable
            setOnClickListener { takePhoto() }
        }

        val params = ConstraintLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.shutter_size),
            resources.getDimensionPixelSize(R.dimen.shutter_size)
        ).apply {
            bottomToBottom = ConstraintSet.PARENT_ID
            startToStart   = ConstraintSet.PARENT_ID
            endToEnd       = ConstraintSet.PARENT_ID
            bottomMargin   = resources.getDimensionPixelSize(R.dimen.shutter_margin_bottom)
        }
        binding.root.addView(captureButton, params)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val tempFile = File(cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraApp", "임시 캡처 저장: ${tempFile.absolutePath}")
                    // 활성화된 기능 조합을 메타데이터 문자열로 구성하여 전달한다.
                    val modeStr = when {
                        isObjectDetectionOn && isCategoryClassificationOn -> "객체+카테고리"
                        isObjectDetectionOn                               -> "객체 감지"
                        isCategoryClassificationOn                        -> "카테고리 분류"
                        else                                              -> "기본"
                    }
                    val intent = Intent(this@MainActivity, ReviewActivity::class.java).apply {
                        putExtra(ReviewActivity.EXTRA_TEMP_FILE_PATH, tempFile.absolutePath)
                        putExtra(ReviewActivity.EXTRA_MODE, modeStr)
                    }
                    startActivity(intent)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraApp", "사진 캡처 실패: ${exc.message}", exc)
                    Toast.makeText(baseContext, "촬영 실패", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ── 카메라 / 감지 ────────────────────────────────────────────────────────────

    /**
     * CameraX 파이프라인을 초기화하고 카메라를 시작한다.
     *
     * ## 유즈케이스 구성
     * - [Preview]: 화면에 실시간 프리뷰를 표시한다.
     * - [ImageCapture]: 셔터 버튼 클릭 시 정지 사진을 촬영한다.
     * - [ImageAnalysis]: 매 프레임 [detectObjects]를 호출하여 AI 추론을 실행한다.
     *   - STRATEGY_KEEP_ONLY_LATEST: 추론이 느려져 프레임이 쌓이면 중간 프레임을 버린다.
     *     최신 프레임만 처리하므로 추론 지연이 화면 지연으로 이어지지 않는다.
     *   - OUTPUT_IMAGE_FORMAT_RGBA_8888: MediaPipe BitmapImageBuilder가 요구하는 포맷.
     *
     * ## 비율 설정 (RATIO_4_3)
     * 3:4 비율로 고정하여 480×640 이미지 좌표계와 일치시킨다.
     * 이 비율을 유지해야 바운딩 박스 좌표 변환이 정확하다.
     */
    private fun startCamera() {
        cameraExecutor.execute { setupObjectDetector() }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                detectObjects(imageProxy)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * MediaPipe ObjectDetector를 초기화한다. 백그라운드 스레드([cameraExecutor])에서 호출된다.
     *
     * ## 모델
     * EfficientDet-Lite0 (efficientdet_lite0.tflite) — assets에 번들됨.
     * 90개 COCO 클래스를 감지하며 모바일에서 실시간 처리가 가능한 경량 모델이다.
     *
     * ## 설정
     * - scoreThreshold = 0.5f: 신뢰도 50% 미만 결과는 무시한다.
     *   너무 낮으면 오감지가 많아지고, 너무 높으면 실제 객체를 놓친다.
     * - RunningMode.LIVE_STREAM: 비동기 처리 — detectAsync로 추론을 요청하고
     *   결과는 setResultListener 콜백으로 수신한다.
     *   UI 스레드 블로킹 없이 카메라 프레임을 계속 수신할 수 있다.
     * - Delegate.CPU: GPU 가속 없이 CPU만 사용.
     *   기기 호환성을 위해 CPU로 고정 (GPU 델리게이트는 일부 기기에서 초기화 실패).
     */
    private fun setupObjectDetector() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .setDelegate(Delegate.CPU)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(0.5f)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                runOnUiThread { onDetectionResult(result) }
            }
            .build()

        objectDetector = ObjectDetector.createFromOptions(this, options)
    }

    /**
     * 매 프레임 감지 결과를 처리한다. UI 스레드에서 호출된다.
     *
     * ## 피사체 미선택 (일반 모드)
     * OverlayView에 전체 결과를 전달하여 모든 박스를 표시한다.
     *
     * ## 피사체 선택됨 (추적 모드)
     * - **매 프레임**: [findTrackedBox]로 피사체를 찾고 [lastKnownCenter]를 갱신한다.
     *   [checkMatchState]로 가이드 박스와의 매칭 여부를 판단한다.
     * - **[FRAME_UPDATE_INTERVAL] 프레임마다**: OverlayView의 추적 박스와 [guideBox]를
     *   최신 위치·크기로 갱신한다. 이 주기보다 짧게 갱신되지 않으므로 박스가 안정적이다.
     */
    private fun onDetectionResult(result: ObjectDetectorResult) {
        if (!isObjectDetectionOn) return

        if (selectedSubjectLabel == null) {
            // ── 일반 모드: 모든 박스를 OverlayView에 표시 ──────────────────────
            binding.overlayView.setResults(result)
            return
        }

        // ── 추적 모드 ──────────────────────────────────────────────────────────
        val trackedBox = findTrackedBox(result)
        if (trackedBox != null) {
            // 매 프레임: 피사체 위치·박스 갱신.
            // lastKnownBox는 findTrackedBox의 IoU 및 거리 필터 기준으로 사용되므로
            // lastKnownCenter와 반드시 함께 갱신해야 한다.
            val center = PointF(trackedBox.centerX(), trackedBox.centerY())
            lastKnownCenter = center
            lastKnownBox    = trackedBox

            // 매 프레임: 가이드 박스와의 매칭 상태 확인 (2초 타이머 정밀도)
            if (guideBox != null) checkMatchState(center)

            // FRAME_UPDATE_INTERVAL 프레임마다: 시각적 박스 위치·크기 갱신
            frameCounter++
            if (frameCounter % FRAME_UPDATE_INTERVAL == 0) {
                binding.overlayView.setTrackedBox(trackedBox, selectedSubjectLabel)

                val comp = selectedComposition
                if (comp != null) {
                    val newGuide = CompositionGuideCalculator.computeTargetBox(trackedBox, comp)
                    guideBox = newGuide
                    // setGuide() 대신 updateGuideBox() 사용 — matchState를 IDLE로 리셋하지 않음.
                    // setGuide()를 쓰면 MATCHED 타이머가 매 FRAME_UPDATE_INTERVAL 프레임마다
                    // 강제로 초기화되어 2초 조건이 절대 충족되지 않는 버그가 생긴다.
                    binding.guideOverlay.updateGuideBox(newGuide, comp)
                }
            }
        }
        // 피사체가 프레임에서 사라진 경우: 마지막 위치를 유지하며 상태 변화 없음
    }

    private fun detectObjects(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        val rotatedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        imageProxy.close()

        // ── 객체 감지 토글 ON: MediaPipe 실시간 감지 ───────────────────────────
        // detectAsync는 비동기로 처리되므로 아래 카테고리 분류와 병렬 실행된다.
        if (isObjectDetectionOn && objectDetector != null) {
            objectDetector?.detectAsync(
                BitmapImageBuilder(rotatedBitmap).build(),
                System.currentTimeMillis()
            )
        }

        // ── 카테고리 분류 토글 ON: LandscapeClassifier 구도 분류 ─────────────────
        // 분류기가 초기화되지 않았거나 이전 추론이 진행 중이면 이 프레임을 건너뛴다.
        if (isCategoryClassificationOn) {
            val classifier = landscapeClassifier ?: return
            if (!isInferenceRunning.compareAndSet(false, true)) return

            val result = classifier.classify(rotatedBitmap)
            isInferenceRunning.set(false)

            result?.let { updateLandscapeScore(it) }
        }
    }

    /**
     * 풍경 모드 추론 결과를 UI에 반영한다. UI 스레드에서 실행된다.
     *
     * 수치 점수 대신 3단계 정성적 상태 레이블을 표시하여 사용자가 직관적으로 구도를 파악하도록 한다.
     *
     * ## 상태 기준 (softmax 확률 × 100)
     * | 점수 범위 | 상태 레이블 | 색상       |
     * |-----------|-------------|------------|
     * | 0 ~ 59    | 불안정      | #FF5252 (빨강) |
     * | 60 ~ 79   | 안정        | #FFD740 (노랑) |
     * | 80 ~ 100  | 최적        | #69F0AE (초록) |
     *
     * [tvCompositionName]에는 구도 이름(예: "삼등분 법칙"),
     * [tvLandscapeScore]에는 상태 레이블(예: "최적")이 표시된다.
     * 두 TextView 모두 동일한 색상을 적용하여 시각적 일관성을 유지한다.
     */
    private fun updateLandscapeScore(result: LandscapeResult) {
        runOnUiThread {
            // 구도 이름은 그대로 표시
            binding.tvCompositionName.text = result.label

            // 점수 → 상태 레이블 + 색상 결정
            val (stateText, color) = when {
                result.score < 60 -> "불안정" to Color.parseColor("#FF5252")
                result.score < 80 -> "안정"   to Color.parseColor("#FFD740")
                else              -> "최적"   to Color.parseColor("#69F0AE")
            }
            binding.tvLandscapeScore.text = stateText
            binding.tvCompositionName.setTextColor(color)
            binding.tvLandscapeScore.setTextColor(color)
        }
    }

    /**
     * 앱에 필요한 모든 권한([REQUIRED_PERMISSIONS])이 허용되어 있는지 확인한다.
     *
     * @return 모든 권한이 GRANTED 상태이면 true, 하나라도 거부되어 있으면 false
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 액티비티 소멸 시 모든 리소스를 해제한다.
     *
     * - [matchHandler]: 등록된 모든 Runnable을 취소하여 메모리 릭을 방지한다.
     * - [cameraExecutor]: 백그라운드 스레드 풀을 종료한다.
     * - [objectDetector]: MediaPipe 네이티브 리소스(C++ 모델 메모리)를 해제한다.
     * - [landscapeClassifier]: PyTorch 네이티브 모듈 리소스를 해제한다.
     *   null일 수 있으므로 safe call(?.)로 호출한다.
     */
    override fun onDestroy() {
        super.onDestroy()
        matchHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        objectDetector?.close()
        landscapeClassifier?.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        /** 피사체가 가이드 박스 안에 유지되어야 하는 최소 시간 (밀리초) */
        private const val MATCH_HOLD_MS = 2000L

        /**
         * 연속 프레임 간 피사체가 이동 가능한 최대 거리 (픽셀, 480×640 이미지 좌표계 기준).
         *
         * ## 역할
         * [findTrackedBox]의 1단계 필터로 사용된다.
         * 이 거리를 초과한 위치의 동명 객체는 피사체와 다른 객체로 간주하고 후보에서 제외한다.
         *
         * ## 값 선택 근거 (120 px = 화면 폭의 25%)
         * - 30fps 기준 빠른 이동: 프레임당 약 40~80 px
         * - 120 px이면 실제 이동 가능한 최대 속도를 충분히 허용하면서,
         *   화면 반대편 동명 객체로의 '순간이동' 전환은 차단한다.
         * - 피사체가 일시 미감지 후 재등장해도 동일 영역이면 재추적된다.
         */
        private const val MAX_TRACKING_DISTANCE = 120f

        /**
         * [MAX_TRACKING_DISTANCE]의 제곱값.
         * [findTrackedBox]에서 거리 비교 시 sqrt 연산을 생략하기 위해 사용한다.
         * (거리² ≤ 거리_상한² ↔ 거리 ≤ 거리_상한, 두 비교는 동치)
         */
        private const val MAX_TRACKING_DIST_SQ  = MAX_TRACKING_DISTANCE * MAX_TRACKING_DISTANCE

        /**
         * TYPE_ACCELEROMETER 폴백 시 적용하는 저역통과 필터 계수 (0 < α ≤ 1).
         *
         * filtered_t = α × raw_t + (1 − α) × filtered_(t-1)
         * α = 0.15 → 시정수 τ ≈ 380 ms.
         * 손 떨림(~5-10 Hz) 제거에 적합하며, 천천히 기울이는 동작(< 1 Hz)은 충분히 추적한다.
         * TYPE_GRAVITY는 하드웨어 필터가 있어 이 값을 사용하지 않는다.
         */
        private const val LP_ALPHA = 0.15f

        /**
         * 매칭 판정 허용 반경의 상한 비율 (이미지 크기 대비).
         * 수평 상한 = 480 × 0.15 = 72 px / 수직 상한 = 640 × 0.15 = 96 px.
         * 삼등분 교차점 인접 영역이 서로 겹치지 않도록 설정된 값이다.
         */
        private const val MATCH_MAX_HALF_RATIO = 0.15f

        /** 매칭 허용 반경의 수평 상한 (픽셀). = 480 × [MATCH_MAX_HALF_RATIO] = 72 px */
        private const val MATCH_MAX_HALF_X = 480f * MATCH_MAX_HALF_RATIO

        /** 매칭 허용 반경의 수직 상한 (픽셀). = 640 × [MATCH_MAX_HALF_RATIO] = 96 px */
        private const val MATCH_MAX_HALF_Y = 640f * MATCH_MAX_HALF_RATIO

        /**
         * 시각적 박스(OverlayView 추적 박스, guideBox)를 갱신하는 프레임 주기.
         * 값이 클수록 박스가 더 천천히 변동하여 시각적으로 안정된다.
         */
        private const val FRAME_UPDATE_INTERVAL = 3
    }
}
