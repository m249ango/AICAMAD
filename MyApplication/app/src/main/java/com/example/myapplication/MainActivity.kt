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
import android.content.Intent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private var isObjectDetectionEnabled = true

    // 사진 촬영을 위한 변수
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
     * 같은 라벨의 여러 박스 중 가장 가까운 것을 피사체로 특정하기 위해 유지한다.
     */
    private var lastKnownCenter: PointF? = null

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
     *
     * [기여] 느린 박스 업데이트(시각적 안정성) 도입.
     */
    private var frameCounter = 0

    // ────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupTopModeButtons()
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
                    val guideCenter = PointF(center.x, center.y)
                    val halfW = 80f; val halfH = 100f  // 초기 근사 크기
                    val approxBox = RectF(
                        guideCenter.x - halfW, guideCenter.y - halfH,
                        guideCenter.x + halfW, guideCenter.y + halfH
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
     *
     * [기여] 포커스 해제 버튼 신규 도입.
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

    /**
     * 피사체 포커스를 해제하고 일반 모드로 복귀한다.
     *
     * - OverlayView를 전체 박스 표시 모드로 전환
     * - 가이드 박스 및 매칭 타이머 초기화
     * - compositionBar 숨김
     * - 모든 선택 상태 필드 초기화
     */
    private fun unfocusSubject() {
        // 피사체 선택 상태 초기화
        selectedSubjectLabel = null
        lastKnownCenter      = null
        selectedComposition  = null
        frameCounter         = 0

        // OverlayView → 전체 박스 표시 모드로 복귀
        binding.overlayView.clearSelection()

        // 가이드 박스 및 매칭 타이머 초기화
        clearGuide()

        // compositionBar (구도 선택 + 포커스 해제 버튼) 숨김
        binding.compositionBar.visibility = View.GONE
    }

    // ── OverlayView 터치 콜백 설정 ────────────────────────────────────────────

    /**
     * OverlayView의 터치 콜백 두 가지를 등록한다.
     *
     * - [OverlayView.onDetectionSelected]: 터치 지점에 객체 1개 → [selectSubject]로 즉시 선택.
     * - [OverlayView.onMultipleDetectionsFound]: 터치 지점에 객체 2개 이상 → [showCandidatePopup]
     *   으로 사용자에게 선택을 위임한다.
     *
     * [기여] 중첩 객체 선택 UI 도입 — 복수 후보 처리 분기 추가.
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
     * [기여] 중첩 객체 선택 UI 도입 — 선택 로직을 공통 함수로 분리.
     *
     * @param index OverlayView.selectedIndex 에 설정할 감지 결과 인덱스
     * @param box   피사체 바운딩 박스 (480×640 이미지 좌표계)
     * @param label 카테고리 라벨
     */
    private fun selectSubject(index: Int, box: RectF, label: String) {
        selectedSubjectLabel = label
        lastKnownCenter      = PointF(box.centerX(), box.centerY())
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
     * [기여] 중첩 객체 선택 UI 신규 도입.
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
        guideBox     = newGuide
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
     * 같은 라벨을 가진 박스 중 [lastKnownCenter]에 가장 가까운 것을 반환한다.
     * 피사체가 프레임에서 사라진 경우 null을 반환한다.
     *
     * @return 추적된 박스 (480×640 이미지 좌표계), 없으면 null
     */
    private fun findTrackedBox(result: ObjectDetectorResult): RectF? {
        val label      = selectedSubjectLabel ?: return null
        val prevCenter = lastKnownCenter      ?: return null

        return result.detections()
            .filter { det -> det.categories().any { it.categoryName() == label } }
            .minByOrNull { det ->
                val dx = det.boundingBox().centerX() - prevCenter.x
                val dy = det.boundingBox().centerY() - prevCenter.y
                dx * dx + dy * dy
            }
            ?.let { RectF(it.boundingBox()) }
    }

    /**
     * 피사체 중심 좌표를 받아 [guideBox]와의 매칭 상태를 판단하고 UI를 갱신한다.
     *
     * - 중심이 [guideBox] 안 → MATCHED 상태 + 2초 타이머 시작
     * - 중심이 [guideBox] 밖 → IDLE 복귀 + 타이머 취소 (RECOMMEND 이후엔 유지)
     *
     * @param subjectCenter 현재 프레임의 피사체 중심 (480×640 이미지 좌표계)
     */
    private fun checkMatchState(subjectCenter: PointF) {
        val guide = guideBox ?: return

        if (guide.contains(subjectCenter.x, subjectCenter.y)) {
            // 피사체 중심이 가이드 박스 안에 있음
            if (!isMatched) {
                isMatched = true

                if (!isRecommended) {
                    // MATCHED 상태 전환 및 2초 타이머 시작
                    binding.guideOverlay.setMatchState(MatchState.MATCHED)

                    val runnable = Runnable {
                        // 2초 연속 유지 완료 → RECOMMEND
                        isRecommended = true
                        binding.guideOverlay.setMatchState(MatchState.RECOMMEND)
                    }
                    matchRunnable = runnable
                    matchHandler.postDelayed(runnable, MATCH_HOLD_MS)
                }
            }
        } else {
            // 피사체 중심이 가이드 박스 밖으로 이탈
            if (isMatched) {
                isMatched = false
                if (!isRecommended) {
                    // RECOMMEND 전에 이탈했으면 타이머 취소 + IDLE 복귀
                    resetMatchTimer()
                    binding.guideOverlay.setMatchState(MatchState.IDLE)
                }
                // 이미 RECOMMEND 상태면 유지 (사용자가 촬영할 때까지 표시)
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

    // ── 기존 UI 설정 ────────────────────────────────────────────────────────────

    private fun setupTopModeButtons() {
        // 풍경 모드: 구도 분류 AI 추론 + 우상단 결과 표시
        binding.btnLandscapeMode.setOnClickListener {
            isObjectDetectionEnabled = false
            binding.btnObjectMode.alpha = 0.5f
            binding.btnLandscapeMode.alpha = 1.0f
            binding.overlayView.visibility = View.GONE

            // 사물 모드 관련 상태 초기화
            unfocusSubject()

            // 풍경 점수 패널 표시 및 초기화
            binding.landscapeScoreLayout.visibility = View.VISIBLE
            binding.tvCompositionName.text = "분석 중…"
            binding.tvLandscapeScore.text  = "—"

            // 분류기 지연 초기화 (최초 1회만 모델 로드)
            if (landscapeClassifier == null) {
                cameraExecutor.execute {
                    landscapeClassifier = LandscapeClassifier(this)
                }
            }

            Toast.makeText(this, "풍경 촬영 모드", Toast.LENGTH_SHORT).show()
        }

        // 사물 모드: 풍경 패널 숨김
        binding.btnObjectMode.setOnClickListener {
            isObjectDetectionEnabled = true
            binding.btnObjectMode.alpha = 1.0f
            binding.btnLandscapeMode.alpha = 0.5f
            binding.overlayView.visibility = View.VISIBLE
            binding.landscapeScoreLayout.visibility = View.GONE
            Toast.makeText(this, "사물 인식 모드", Toast.LENGTH_SHORT).show()
        }
    }

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

        // 임시 파일에 저장 → ReviewActivity 에서 API 분석 후 저장 여부 결정
        val tempFile = File(cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(tempFile)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraApp", "임시 캡처 저장: ${tempFile.absolutePath}")
                    val intent = Intent(this@MainActivity, ReviewActivity::class.java).apply {
                        putExtra(ReviewActivity.EXTRA_TEMP_FILE_PATH, tempFile.absolutePath)
                        // 현재 활성 모드를 메타데이터로 전달
                        putExtra(
                            ReviewActivity.EXTRA_MODE,
                            if (isObjectDetectionEnabled) "사물" else "풍경"
                        )
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
                // 사물 모드와 풍경 모드 모두 detectObjects에서 분기 처리
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
     *
     * [기여] 느린 박스 업데이트 및 피사체만 표시 기능 도입.
     */
    private fun onDetectionResult(result: ObjectDetectorResult) {
        if (!isObjectDetectionEnabled) return

        if (selectedSubjectLabel == null) {
            // ── 일반 모드: 모든 박스를 OverlayView에 표시 ──────────────────────
            binding.overlayView.setResults(result)
            return
        }

        // ── 추적 모드 ──────────────────────────────────────────────────────────
        val trackedBox = findTrackedBox(result)
        if (trackedBox != null) {
            // 매 프레임: 피사체 중심 위치 갱신 (매칭 정확도 유지)
            val center = PointF(trackedBox.centerX(), trackedBox.centerY())
            lastKnownCenter = center

            // 매 프레임: 가이드 박스와의 매칭 상태 확인 (2초 타이머 정밀도)
            if (guideBox != null) checkMatchState(center)

            // FRAME_UPDATE_INTERVAL 프레임마다: 시각적 박스 위치·크기 갱신
            frameCounter++
            if (frameCounter % FRAME_UPDATE_INTERVAL == 0) {
                // OverlayView의 추적 박스 갱신 (선택된 박스 하나만 표시)
                binding.overlayView.setTrackedBox(trackedBox, selectedSubjectLabel)

                // 구도가 선택된 경우, 가이드 박스도 피사체 크기에 맞게 재계산
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

        if (isObjectDetectionEnabled) {
            // ── 사물 모드: MediaPipe 객체 감지 ──────────────────────────────
            if (objectDetector == null) return
            objectDetector?.detectAsync(
                BitmapImageBuilder(rotatedBitmap).build(),
                System.currentTimeMillis()
            )
        } else {
            // ── 풍경 모드: PyTorch Mobile 구도 분류 ─────────────────────────
            // 분류기가 아직 초기화되지 않았거나 이전 추론이 진행 중이면 프레임을 건너뜀
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
     * 점수에 따라 텍스트 색상을 변경한다:
     * - 0~59점: 빨강 (#FF5252)
     * - 60~79점: 노랑 (#FFD740)
     * - 80~100점: 초록 (#69F0AE)
     */
    private fun updateLandscapeScore(result: LandscapeResult) {
        runOnUiThread {
            binding.tvCompositionName.text = result.label
            binding.tvLandscapeScore.text  = "${result.score}점"

            val color = when {
                result.score < 60 -> Color.parseColor("#FF5252")
                result.score < 80 -> Color.parseColor("#FFD740")
                else              -> Color.parseColor("#69F0AE")
            }
            binding.tvCompositionName.setTextColor(color)
            binding.tvLandscapeScore.setTextColor(color)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

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
         * 시각적 박스(OverlayView 추적 박스, guideBox)를 갱신하는 프레임 주기.
         * 감지 프레임 수 기준이며, 값이 클수록 박스가 더 천천히 변동한다.
         *
         * [기여] 느린 박스 업데이트 — 급격한 크기 변동 방지.
         */
        private const val FRAME_UPDATE_INTERVAL = 3
    }
}
