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

    private var isObjectDetectionOn = true
    private var isCategoryClassificationOn = false

    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null  // TYPE_GRAVITY 없는 기기 폴백
    private var lpGx = 0f
    private var lpGy = -9.8f  // portrait 직립 초기값 — 첫 프레임 이상 각도 방지

    private var imageCapture: ImageCapture? = null

    private var landscapeClassifier: LandscapeClassifier? = null
    private val isInferenceRunning = AtomicBoolean(false)  // 추론 중복 실행 방지

    private var selectedSubjectLabel: String? = null
    private var lastKnownCenter: PointF? = null
    private var lastKnownBox: RectF? = null  // IoU + 거리 필터 기준 (lastKnownCenter와 함께 갱신)
    private var selectedComposition: Composition? = null
    private var guideBox: RectF? = null

    private var isMatched = false
    private val matchHandler = Handler(Looper.getMainLooper())
    private var matchRunnable: Runnable? = null
    private var isRecommended = false

    private var frameCounter = 0

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

                val center = lastKnownCenter
                if (center != null) {
                    // 현재 위치로 임시 박스 생성 — 다음 프레임에 정확히 갱신됨
                    val halfW = 80f; val halfH = 100f
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

    private fun setupUnfocusButton() {
        binding.btnUnfocus.setOnClickListener { unfocusSubject() }
    }

    private fun setupGalleryButton() {
        binding.btnOpenGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    private fun setupSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Log.d("MainActivity", "TYPE_GRAVITY 없음 — TYPE_ACCELEROMETER 폴백 사용")
        }
    }

    override fun onResume() {
        super.onResume()
        val sensor = gravitySensor ?: accelerometerSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx: Float
        val gy: Float

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                // 하드웨어 필터 내장 → 직접 사용
                gx = event.values[0]
                gy = event.values[1]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 소프트웨어 저역통과 필터 (LP_ALPHA = 0.15)
                lpGx = LP_ALPHA * event.values[0] + (1f - LP_ALPHA) * lpGx
                lpGy = LP_ALPHA * event.values[1] + (1f - LP_ALPHA) * lpGy
                gx = lpGx
                gy = lpGy
            }
            else -> return
        }

        // 세로 portrait 기준: atan2(gx, -gy) → 오른쪽 기울기 = 양수
        val roll = Math.toDegrees(atan2(gx.toDouble(), (-gy).toDouble())).toFloat()
        binding.levelIndicator.update(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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

    private fun setupOverlayTouchCallback() {
        binding.overlayView.onDetectionSelected = { index, box, label ->
            selectSubject(index, box, label)
        }
        binding.overlayView.onMultipleDetectionsFound = { candidates, touchX, touchY ->
            showCandidatePopup(candidates, touchX, touchY)
        }
    }

    private fun selectSubject(index: Int, box: RectF, label: String) {
        selectedSubjectLabel = label
        lastKnownCenter      = PointF(box.centerX(), box.centerY())
        lastKnownBox         = RectF(box)
        frameCounter         = 0

        binding.overlayView.selectedIndex = index
        binding.overlayView.setTrackedBox(box, label)
        binding.compositionBar.visibility = View.VISIBLE

        val comp = selectedComposition
        if (comp != null) showGuide(box, comp) else clearGuide()
    }

    private fun showCandidatePopup(
        candidates: List<DetectionCandidate>,
        touchX: Float,
        touchY: Float
    ) {
        // 팝업 항목: "고양이  87%" 형식
        val displayItems = candidates.map { "${it.label}  ${it.score}%" }

        val popup = ListPopupWindow(this)
        popup.anchorView = binding.overlayView
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems))
        popup.width = (200 * resources.displayMetrics.density).toInt()
        // verticalOffset: anchorView 하단 기준 → touchY - overlayView.height 로 터치 위치 정렬
        popup.horizontalOffset = touchX.toInt()
        popup.verticalOffset   = touchY.toInt() - binding.overlayView.height
        popup.isModal = true

        popup.setOnItemClickListener { _, _, position, _ ->
            val selected = candidates[position]
            selectSubject(selected.index, selected.box, selected.label)
            popup.dismiss()
        }

        popup.show()
    }

    private fun showGuide(subjectBox: RectF, comp: Composition) {
        val newGuide = CompositionGuideCalculator.computeTargetBox(subjectBox, comp)
        guideBox      = newGuide
        isRecommended = false
        resetMatchTimer()

        binding.guideOverlay.visibility = View.VISIBLE
        binding.guideOverlay.setGuide(newGuide, comp)
    }

    private fun clearGuide() {
        guideBox      = null
        isRecommended = false
        resetMatchTimer()

        binding.guideOverlay.clearGuide()
        binding.guideOverlay.visibility = View.GONE
    }

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

        // ② IoU + 거리 복합 점수 최솟값 후보 선택
        // score = (1 - IoU) × 0.6 + normDist × 0.4
        return candidates.minByOrNull { det ->
            val box      = RectF(det.boundingBox())
            val iou      = computeIoU(prevBox, box)
            val dx       = box.centerX() - prevCx
            val dy       = box.centerY() - prevCy
            val normDist = (dx * dx + dy * dy) / MAX_TRACKING_DIST_SQ
            (1f - iou) * 0.6f + normDist * 0.4f
        }?.let { RectF(it.boundingBox()) }
    }

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

    private fun resetMatchTimer() {
        matchRunnable?.let { matchHandler.removeCallbacks(it) }
        matchRunnable = null
        isMatched     = false
    }

    private fun setupFeatureToggles() {
        binding.switchObjectMode.setOnCheckedChangeListener { _, isChecked ->
            isObjectDetectionOn = isChecked
            if (isChecked) {
                binding.overlayView.visibility = View.VISIBLE
            } else {
                binding.overlayView.visibility = View.GONE
                unfocusSubject()
            }
            Toast.makeText(this, if (isChecked) "객체 감지 ON" else "객체 감지 OFF", Toast.LENGTH_SHORT).show()
        }

        binding.switchLandscapeMode.setOnCheckedChangeListener { _, isChecked ->
            isCategoryClassificationOn = isChecked
            if (isChecked) {
                binding.landscapeScoreLayout.visibility = View.VISIBLE
                binding.tvCompositionName.text = "분석 중…"
                binding.tvLandscapeScore.text  = "—"
                // 최초 1회만 백그라운드에서 모델 로드
                if (landscapeClassifier == null) {
                    cameraExecutor.execute { landscapeClassifier = LandscapeClassifier(this) }
                }
            } else {
                // OFF: 패널 숨김 (모델은 메모리에 유지)
                binding.landscapeScoreLayout.visibility = View.GONE
            }
            Toast.makeText(this, if (isChecked) "카테고리 분류 ON" else "카테고리 분류 OFF", Toast.LENGTH_SHORT).show()
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

        val tempFile = File(cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraApp", "임시 캡처 저장: ${tempFile.absolutePath}")
                    // 활성화된 기능 조합을 모드 문자열로 구성
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

    private fun startCamera() {
        cameraExecutor.execute {
            try { setupObjectDetector() }
            catch (e: Exception) { Log.e("MainActivity", "Object detector 초기화 실패: ${e.message}") }
        }
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
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageAnalysis, imageCapture
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupObjectDetector() {
        val modelBuffer = assets.open("efficientnet_lite0.tflite").use {
            java.nio.ByteBuffer.wrap(it.readBytes())
        }
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(modelBuffer)
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

    private fun onDetectionResult(result: ObjectDetectorResult) {
        if (!isObjectDetectionOn) return

        if (selectedSubjectLabel == null) {
            // 일반 모드: 모든 박스 표시
            binding.overlayView.setResults(result)
            return
        }

        // 추적 모드
        val trackedBox = findTrackedBox(result)
        if (trackedBox != null) {
            val center = PointF(trackedBox.centerX(), trackedBox.centerY())
            lastKnownCenter = center
            lastKnownBox    = trackedBox  // IoU 필터 기준으로도 사용되므로 항상 함께 갱신

            if (guideBox != null) checkMatchState(center)

            frameCounter++
            if (frameCounter % FRAME_UPDATE_INTERVAL == 0) {
                binding.overlayView.setTrackedBox(trackedBox, selectedSubjectLabel)

                val comp = selectedComposition
                if (comp != null) {
                    val newGuide = CompositionGuideCalculator.computeTargetBox(trackedBox, comp)
                    guideBox = newGuide
                    // setGuide() 대신 updateGuideBox() — matchState를 IDLE로 리셋하지 않음
                    // setGuide()를 쓰면 매 N 프레임마다 타이머가 초기화되어 2초 조건 미충족
                    binding.guideOverlay.updateGuideBox(newGuide, comp)
                }
            }
        }
        // 피사체 소실 시: 마지막 위치 유지
    }

    private fun detectObjects(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        val rotatedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        imageProxy.close()

        // 객체 감지 (비동기 — 아래 카테고리 분류와 병렬)
        if (isObjectDetectionOn && objectDetector != null) {
            objectDetector?.detectAsync(
                BitmapImageBuilder(rotatedBitmap).build(),
                System.currentTimeMillis()
            )
        }

        // 카테고리 분류 — 이전 추론 진행 중이면 이 프레임 건너뜀
        if (isCategoryClassificationOn) {
            val classifier = landscapeClassifier ?: return
            if (!isInferenceRunning.compareAndSet(false, true)) return

            val result = classifier.classify(rotatedBitmap)
            isInferenceRunning.set(false)

            result?.let { updateLandscapeScore(it) }
        }
    }

    private fun updateLandscapeScore(result: LandscapeResult) {
        runOnUiThread {
            binding.tvCompositionName.text = result.label
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

        private const val MATCH_HOLD_MS = 2000L  // 피사체가 가이드 안에 유지되어야 하는 시간 (ms)

        private const val MAX_TRACKING_DISTANCE = 120f  // 프레임 간 최대 이동 거리 (픽셀)
        private const val MAX_TRACKING_DIST_SQ  = MAX_TRACKING_DISTANCE * MAX_TRACKING_DISTANCE  // sqrt 생략용

        private const val LP_ALPHA = 0.15f  // 가속도 센서 저역통과 필터 계수

        private const val MATCH_MAX_HALF_RATIO = 0.15f
        private const val MATCH_MAX_HALF_X = 480f * MATCH_MAX_HALF_RATIO  // 매칭 허용 반경 수평 상한 (px)
        private const val MATCH_MAX_HALF_Y = 640f * MATCH_MAX_HALF_RATIO  // 매칭 허용 반경 수직 상한 (px)

        private const val FRAME_UPDATE_INTERVAL = 3  // 시각 박스 갱신 프레임 주기
    }
}
