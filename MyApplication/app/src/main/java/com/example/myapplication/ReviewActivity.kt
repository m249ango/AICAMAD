package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.myapplication.databinding.ActivityReviewBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * 촬영 직후 표시되는 리뷰 화면.
 *
 * ## 동작 흐름
 * 1. [MainActivity.takePhoto]에서 임시 파일 경로([EXTRA_TEMP_FILE_PATH])와
 *    촬영 모드([EXTRA_MODE])를 받는다.
 * 2. 촬영 모드는 즉시 UI에 표시한다.
 * 3. 백그라운드에서 두 가지 분석을 순차 실행한다:
 *    - [LandscapeClassifier]: 온디바이스 구도 분류 → 카테고리 + 확신도
 *    - [AestheticApiClient.predict]: 미학 점수 API 호출
 * 4. 분석 완료 후 결과를 UI에 반영하고 "저장" 버튼을 활성화한다.
 * 5. "저장" → [AppStorage.savePhoto]로 4가지 메타데이터와 함께 갤러리에 보관.
 *    "버리기" → 임시 파일 삭제 후 종료.
 *
 * ## 점수 색상 (미학 점수 기준)
 * - 0~59점: 빨강 (#FF5252)
 * - 60~79점: 노랑 (#FFD740)
 * - 80~100점: 초록 (#69F0AE)
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding

    /** API에서 받은 미학 점수 (0~100). 분석 실패 시 0으로 유지. */
    private var aestheticScore: Int = 0

    /** 온디바이스 분류기에서 받은 카테고리 이름. 분석 실패 시 "—". */
    private var category: String = "—"

    /** 카테고리 확신도 (0~100). 분석 실패 시 0. */
    private var categoryScore: Int = 0

    /** Intent에서 전달받은 촬영 모드 ("사물" or "풍경"). */
    private var mode: String = "—"

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 임시 파일 경로와 촬영 모드 수신
        val tempPath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH)
        if (tempPath == null) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mode = intent.getStringExtra(EXTRA_MODE) ?: "—"

        val tempFile = File(tempPath)

        // edge-to-edge: 내비게이션 바 높이만큼 scrollContent 하단 패딩 추가
        // Material3 테마가 자동으로 edge-to-edge 를 활성화하므로 반드시 보정이 필요하다.
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollContent) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

        // 촬영 모드는 즉시 표시 (분석 불필요)
        binding.tvMode.text = mode

        setupImagePreview(tempFile)
        startAnalysis(tempFile)
        setupButtons(tempFile)
    }

    // ── 이미지 미리보기 ────────────────────────────────────────────────────────

    /**
     * 임시 파일에서 비트맵을 디코딩하여 미리보기 ImageView에 설정한다.
     * 메모리 절약을 위해 화면 크기에 맞게 샘플링하고, EXIF 회전 정보를 적용한다.
     */
    private fun setupImagePreview(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val displayWidth = resources.displayMetrics.widthPixels
        var sampleSize = 1
        while (options.outWidth / sampleSize > displayWidth) sampleSize *= 2

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        binding.ivPreview.setImageBitmap(raw?.let { applyExifRotation(it, file) } ?: raw)
    }

    // ── 분석 (미학 점수 + 카테고리) ──────────────────────────────────────────

    /**
     * 백그라운드 스레드에서 두 가지 분석을 순차 실행한다.
     *
     * 1. [LandscapeClassifier]: 온디바이스, 빠름 (~수십 ms)
     *    → 구도 카테고리 이름 + 확신도(0~100)
     * 2. [AestheticApiClient.predict]: 네트워크, 느림 (~수 초)
     *    → 미학 점수(0~100)
     *
     * LandscapeClassifier 가 먼저 끝나도 API 응답을 기다린 후 한 번에 UI 업데이트한다.
     */
    private fun startAnalysis(file: File) {
        analysisExecutor.execute {
            // ① 구도 카테고리 분류 (온디바이스)
            val landscapeResult = runCatching {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val classifier = LandscapeClassifier(this)
                val result = bitmap?.let { classifier.classify(it) }
                classifier.close()
                result
            }.getOrNull()

            // ② 미학 점수 (API)
            val apiScore = AestheticApiClient.predict(file)

            runOnUiThread { onAnalysisComplete(apiScore, landscapeResult) }
        }
    }

    /**
     * 두 분석이 모두 완료된 후 UI를 업데이트하고 "저장" 버튼을 활성화한다.
     */
    private fun onAnalysisComplete(apiScore: Int?, landscapeResult: LandscapeResult?) {
        binding.progressBar.visibility = View.GONE

        // 미학 점수
        if (apiScore != null) {
            aestheticScore = apiScore
            binding.tvScore.text = "${apiScore}점"
            binding.tvScore.setTextColor(scoreColor(apiScore))
        } else {
            aestheticScore = 0
            binding.tvScore.text = "분석 실패"
            binding.tvScore.setTextColor(Color.parseColor("#AAAAAA"))
        }

        // 카테고리
        if (landscapeResult != null) {
            category      = landscapeResult.label
            categoryScore = landscapeResult.score
            binding.tvCategory.text      = landscapeResult.label
            binding.tvCategory.setTextColor(Color.WHITE)
            binding.tvCategoryScore.text = "${landscapeResult.score}점"
        } else {
            category      = "—"
            categoryScore = 0
            binding.tvCategory.text      = "분류 실패"
            binding.tvCategory.setTextColor(Color.parseColor("#AAAAAA"))
            binding.tvCategoryScore.text = ""
        }

        binding.btnSave.isEnabled = true
    }

    // ── 버튼 처리 ─────────────────────────────────────────────────────────────

    /**
     * 저장·버리기 버튼의 클릭 동작을 등록한다.
     *
     * ## 저장 버튼
     * [AppStorage.savePhoto]를 호출하여 임시 파일을 갤러리 디렉터리로 복사하고
     * 4가지 메타데이터(미학 점수, 카테고리, 카테고리 점수, 촬영 모드)를 JSON으로 저장한다.
     * 저장 완료 후 임시 파일을 즉시 삭제하여 캐시 디렉터리를 정리한다.
     *
     * ## 버리기 버튼
     * 분석 결과를 무시하고 임시 파일만 삭제한 뒤 액티비티를 종료한다.
     * 사용자가 사진 품질에 만족하지 않을 때 갤러리를 오염시키지 않도록 한다.
     *
     * @param tempFile [MainActivity.takePhoto]가 생성한 임시 JPEG 파일
     */
    private fun setupButtons(tempFile: File) {
        // 저장: 4가지 메타데이터와 함께 갤러리에 보관
        binding.btnSave.setOnClickListener {
            val saved = AppStorage.savePhoto(
                context       = this,
                tempFile      = tempFile,
                score         = aestheticScore,
                category      = category,
                categoryScore = categoryScore,
                mode          = mode
            )
            tempFile.delete()

            Toast.makeText(
                this,
                if (saved != null) "갤러리에 저장되었습니다." else "저장에 실패했습니다.",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        // 버리기: 임시 파일만 삭제하고 종료
        binding.btnDiscard.setOnClickListener {
            tempFile.delete()
            finish()
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    /** 점수 구간별 텍스트 색상을 반환한다. */
    private fun scoreColor(score: Int) = when {
        score < 60 -> Color.parseColor("#FF5252")  // 빨강
        score < 80 -> Color.parseColor("#FFD740")  // 노랑
        else       -> Color.parseColor("#69F0AE")  // 초록
    }

    /**
     * EXIF 회전 정보를 읽어 비트맵을 올바른 방향으로 회전한다.
     *
     * CameraX는 픽셀을 회전하지 않고 EXIF 메타데이터에만 회전 방향을 기록하므로,
     * 표시 전 반드시 이 함수로 보정해야 세로 이미지가 바르게 나타난다.
     */
    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val degrees = when (
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    /**
     * 액티비티 소멸 시 분석 스레드를 종료한다.
     *
     * [analysisExecutor]를 shutdown하지 않으면 분석이 완료되지 않은 채
     * 스레드가 백그라운드에 살아남아 메모리 릭이 발생할 수 있다.
     */
    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    companion object {
        /** Intent Extra 키: 임시 캡처 파일의 절대 경로 */
        const val EXTRA_TEMP_FILE_PATH = "extra_temp_file_path"

        /** Intent Extra 키: 촬영 모드 ("사물" or "풍경") */
        const val EXTRA_MODE = "extra_mode"
    }
}
