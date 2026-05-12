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
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
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
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private var isObjectDetectionEnabled = true

    // 사진 촬영을 위한 변수
    private var imageCapture: ImageCapture? = null

    // ── 구도 모드 상태 필드 ─────────────────────────────────────────────────────

    /**
     * 사용자가 터치로 선택한 피사체의 바운딩 박스 (480×640 이미지 좌표계).
     * 피사체 선택 전에는 null.
     */
    private var selectedSubjectBox: RectF? = null

    /**
     * 선택된 피사체의 카테고리 라벨.
     * 다음 프레임에서 같은 라벨의 박스를 찾아 위치를 추적할 때 사용한다.
     */
    private var selectedSubjectLabel: String? = null

    /**
     * 직전 프레임에서 추적한 피사체 중심 좌표 (480×640).
     * 여러 박스 중 이전 위치와 가장 가까운 박스를 피사체로 특정하기 위해 유지한다.
     */
    private var lastKnownCenter: PointF? = null

    /** 사용자가 선택한 구도 유형. null이면 구도 모드 비활성. */
    private var selectedComposition: Composition? = null

    /**
     * 가이드 박스의 목표 위치 (480×640 이미지 좌표계).
     * computeTargetBox() 결과를 저장하며, 피사체 박스 크기가 바뀌면 재계산한다.
     */
    private var guideBox: RectF? = null

    /**
     * 피사체 중심이 현재 guideBox 안에 있는지 여부.
     * true → MATCHED 타이머 진행 중, false → IDLE 또는 타이머 리셋.
     */
    private var isMatched = false

    /** 2초 연속 매칭 후 RECOMMEND 상태로 전환하는 타이머용 Handler */
    private val matchHandler = Handler(Looper.getMainLooper())

    /**
     * Handler에 등록된 RECOMMEND 전환 Runnable.
     * 피사체가 guideBox 밖으로 나가면 이 Runnable을 취소하여 타이머를 리셋한다.
     */
    private var matchRunnable: Runnable? = null

    /** RECOMMEND 상태에 도달했는지 여부. 도달하면 타이머를 더 이상 재시작하지 않는다. */
    private var isRecommended = false

    // ────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupTopModeButtons()
        setupShutterButton()
        setupCompositionButton()
        setupOverlayTouchCallback()

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
     * 1. selectedComposition 갱신
     * 2. 이미 피사체가 선택된 상태라면 guideBox를 즉시 계산하고 가이드를 표시한다.
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
                val subjectBox = selectedSubjectBox
                if (subjectBox != null) {
                    showGuide(subjectBox, comp)
                }
                true
            }
            menu.show()
        }
    }

    /**
     * OverlayView의 터치 콜백을 등록한다.
     *
     * 사용자가 바운딩 박스를 터치하면:
     * 1. 선택된 피사체 정보(박스, 라벨, 중심)를 저장한다.
     * 2. 구도 선택 버튼을 표시한다.
     * 3. 구도가 이미 선택된 상태라면 가이드 박스를 즉시 재계산한다.
     * 4. 이전 가이드 상태를 초기화(IDLE)한다.
     */
    private fun setupOverlayTouchCallback() {
        binding.overlayView.onDetectionSelected = { _, box, label ->
            selectedSubjectBox = box
            selectedSubjectLabel = label
            lastKnownCenter = PointF(box.centerX(), box.centerY())

            // 구도 버튼 노출 (피사체를 선택해야 의미가 있으므로 이 시점에 공개)
            binding.btnComposition.visibility = View.VISIBLE

            // 구도가 이미 선택된 상태라면 가이드 즉시 갱신
            val comp = selectedComposition
            if (comp != null) {
                showGuide(box, comp)
            } else {
                // 피사체만 선택되고 구도는 미선택 — 기존 가이드를 초기화
                clearGuide()
            }
        }
    }

    // ── 가이드 관리 ─────────────────────────────────────────────────────────────

    /**
     * 피사체 박스와 구도를 받아 guideBox를 계산하고 GuideOverlayView를 IDLE 상태로 표시한다.
     * 매칭 타이머도 초기화된다.
     */
    private fun showGuide(subjectBox: RectF, comp: Composition) {
        val newGuide = CompositionGuideCalculator.computeTargetBox(subjectBox, comp)
        guideBox = newGuide
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
        guideBox = null
        isRecommended = false
        resetMatchTimer()

        binding.guideOverlay.clearGuide()
        binding.guideOverlay.visibility = View.GONE
    }

    // ── 매칭 상태 관리 ──────────────────────────────────────────────────────────

    /**
     * 새 감지 결과를 받아 피사체를 추적하고 매칭 상태를 갱신한다.
     * ObjectDetector 결과 리스너에서 매 프레임 호출된다.
     *
     * 처리 흐름:
     * 1. 구도 선택 + 가이드 박스가 설정된 경우에만 동작.
     * 2. 동일 라벨의 박스 중 lastKnownCenter와 가장 가까운 것을 피사체로 추적.
     * 3. 피사체 중심이 guideBox 안 → MATCHED + 2초 타이머 시작.
     *    피사체 중심이 guideBox 밖 → IDLE + 타이머 취소.
     * 4. 이미 RECOMMEND 상태면 타이머를 다시 걸지 않는다.
     */
    private fun checkMatchState(result: ObjectDetectorResult) {
        val guide        = guideBox             ?: return
        val targetLabel  = selectedSubjectLabel ?: return
        val prevCenter   = lastKnownCenter      ?: return

        // 같은 라벨을 가진 박스들 중 직전 중심 위치와 가장 가까운 것을 피사체로 특정
        val tracked = result.detections()
            .filter { it.categories().any { c -> c.categoryName() == targetLabel } }
            .minByOrNull { det ->
                val cx = det.boundingBox().centerX()
                val cy = det.boundingBox().centerY()
                val dx = cx - prevCenter.x
                val dy = cy - prevCenter.y
                dx * dx + dy * dy
            } ?: return  // 피사체가 프레임에서 사라진 경우 상태 유지

        val center = PointF(tracked.boundingBox().centerX(), tracked.boundingBox().centerY())
        lastKnownCenter = center  // 다음 프레임 추적용 갱신

        if (guide.contains(center.x, center.y)) {
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
     * 매칭 타이머를 취소하고 isMatched를 초기화한다.
     * guideBox를 새로 설정하거나 피사체가 이탈할 때 호출한다.
     */
    private fun resetMatchTimer() {
        matchRunnable?.let { matchHandler.removeCallbacks(it) }
        matchRunnable = null
        isMatched = false
    }

    // ── 기존 UI 설정 ────────────────────────────────────────────────────────────

    private fun setupTopModeButtons() {
        // 사물 모드: 개체 인식
        binding.btnObjectMode.setOnClickListener {
            isObjectDetectionEnabled = true
            binding.btnObjectMode.alpha = 1.0f
            binding.btnLandscapeMode.alpha = 0.5f
            binding.overlayView.visibility = View.VISIBLE
            Toast.makeText(this, "사물 인식 모드", Toast.LENGTH_SHORT).show()
        }

        // 풍경 모드: 미구현
        binding.btnLandscapeMode.setOnClickListener {
            isObjectDetectionEnabled = false
            binding.btnObjectMode.alpha = 0.5f
            binding.btnLandscapeMode.alpha = 1.0f
            binding.overlayView.visibility = View.GONE

            // 풍경 모드로 전환하면 구도 가이드도 숨긴다
            clearGuide()
            binding.btnComposition.visibility = View.GONE
            selectedSubjectBox = null
            selectedSubjectLabel = null
            lastKnownCenter = null
            selectedComposition = null
            binding.overlayView.selectedIndex = -1

            Toast.makeText(this, "풍경 촬영 모드", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupShutterButton() { // 촬영 버튼
        val shutterDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(12, Color.parseColor("#DDDDDD"))
        }

        val captureButton = Button(this).apply {
            id = View.generateViewId()
            background = shutterDrawable
            setOnClickListener {
                takePhoto()
            }
        }

        val params = ConstraintLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.shutter_size),
            resources.getDimensionPixelSize(R.dimen.shutter_size)
        ).apply {
            bottomToBottom = ConstraintSet.PARENT_ID
            startToStart = ConstraintSet.PARENT_ID
            endToEnd = ConstraintSet.PARENT_ID
            bottomMargin = resources.getDimensionPixelSize(R.dimen.shutter_margin_bottom)
        }
        binding.root.addView(captureButton, params)
    }

    // 사진 촬영 핵심 함수
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 파일 이름
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        // MediaStore 설정 (MyApplication 앨범 갤러리 표시)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyApplication")
            }
        }

        // 저장 옵션 설정
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // 실제 촬영 및 저장
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "사진 저장 성공: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d("CameraApp", msg)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraApp", "사진 저장 실패: ${exc.message}", exc)
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

            // ImageCapture 설정 추가
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isObjectDetectionEnabled) detectObjects(imageProxy) else imageProxy.close()
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
        val baseOptions = BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite")
            .setDelegate(Delegate.CPU).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(baseOptions)
            .setScoreThreshold(0.5f)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                runOnUiThread {
                    if (isObjectDetectionEnabled) {
                        binding.overlayView.setResults(result)
                        // 구도 모드가 활성화된 경우(가이드 박스 존재)에만 매칭 상태를 확인한다.
                        if (guideBox != null) checkMatchState(result)
                    }
                }
            }.build()
        objectDetector = ObjectDetector.createFromOptions(this, options)
    }

    private fun detectObjects(imageProxy: ImageProxy) {
        if (objectDetector == null) {
            imageProxy.close(); return
        }
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        val rotatedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        objectDetector?.detectAsync(
            BitmapImageBuilder(rotatedBitmap).build(),
            System.currentTimeMillis()
        )
        imageProxy.close()
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────────

    private fun updateTotalScore(score: Int) {
        runOnUiThread {
            val formattedScore = String.format("%3d%%", score)
            binding.tvTotalScore.text = formattedScore

            val color = when {
                score < 60 -> Color.parseColor("#FF5252")
                score < 80 -> Color.parseColor("#FFD740")
                else       -> Color.parseColor("#69F0AE")
            }
            binding.tvTotalScore.setTextColor(color)

            val background = binding.scoreLayout.background as GradientDrawable
            background.setStroke(2, color)
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
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        /** 피사체가 가이드 박스 안에 유지되어야 하는 최소 시간 (밀리초) */
        private const val MATCH_HOLD_MS = 2000L
    }
}
