package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private var isObjectDetectionEnabled = true

    // 사진 촬영을 위한 변수
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupTopModeButtons()
        setupShutterButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

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
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // 최소지연 모드
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
            .setDelegate(Delegate.CPU).build() // 다운로드한 mediaPipe 모델
        val options = ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(baseOptions)
            .setScoreThreshold(0.5f)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                runOnUiThread {
                    if (isObjectDetectionEnabled) binding.overlayView.setResults(
                        result
                    )
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

    private fun updateTotalScore(score: Int) { // 점수 업뎃
        runOnUiThread {
            val formattedScore = String.format("%3d%%", score) // 결과 예시: "  0%", " 90%", "100%"
            binding.tvTotalScore.text = formattedScore

            // 점수대별 색상 변경
            val color = when {
                score < 60 -> Color.parseColor("#FF5252")
                score < 80 -> Color.parseColor("#FFD740")
                else -> Color.parseColor("#69F0AE")
            }
            binding.tvTotalScore.setTextColor(color)

            // 테두리 색상도 맞춤
            val background = binding.scoreLayout.background as GradientDrawable
            background.setStroke(2, color)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy(); cameraExecutor.shutdown(); objectDetector?.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10;
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}