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
import com.example.myapplication.databinding.ActivityReviewBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * 촬영 직후 표시되는 리뷰 화면.
 *
 * ## 동작 흐름
 * 1. [MainActivity.takePhoto]에서 임시 파일 경로([EXTRA_TEMP_FILE_PATH])를 받아 이미지를 표시한다.
 * 2. 백그라운드에서 [AestheticApiClient.predict]를 호출하여 미학 점수를 받는다.
 * 3. 점수에 따라 텍스트 색상을 적용하고 "저장" 버튼을 활성화한다.
 * 4. "저장" → [AppStorage.savePhoto]로 앱 전용 갤러리에 보관 후 임시 파일 삭제.
 *    "버리기" → 임시 파일만 삭제하고 종료.
 *
 * ## 점수 색상
 * - 0~59점: 빨강 (#FF5252)
 * - 60~79점: 노랑 (#FFD740)
 * - 80~100점: 초록 (#69F0AE)
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding

    /** API에서 받은 미학 점수 (0~100). 분석 실패 시 0으로 유지. */
    private var aestheticScore: Int = 0

    /** 분석 완료 여부. API 응답 전 "저장" 버튼 비활성화에 사용. */
    private var analysisComplete: Boolean = false

    private val apiExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 임시 파일 경로를 Intent에서 수신
        val tempPath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH)
        if (tempPath == null) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val tempFile = File(tempPath)

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
        // 1단계: 원본 크기 측정
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        // 2단계: 화면 너비 기준으로 샘플 크기 계산
        val displayWidth = resources.displayMetrics.widthPixels
        var sampleSize = 1
        while (options.outWidth / sampleSize > displayWidth) sampleSize *= 2

        // 3단계: 샘플링하여 디코딩 후 EXIF 방향 보정
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        binding.ivPreview.setImageBitmap(raw?.let { applyExifRotation(it, file) } ?: raw)
    }

    /**
     * EXIF 회전 정보를 읽어 비트맵을 올바른 방향으로 회전한다.
     *
     * CameraX는 픽셀을 회전하지 않고 EXIF 메타데이터에만 회전 방향을 기록하므로,
     * 표시 전 반드시 이 함수로 보정해야 세로 이미지가 바르게 나타난다.
     *
     * @param bitmap 원본 디코딩된 비트맵
     * @param file   EXIF 정보를 읽을 JPEG 파일
     * @return 회전이 적용된 비트맵 (회전 불필요 시 원본 반환)
     */
    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val degrees = when (
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap   // 회전 불필요
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    // ── 미학 점수 분석 ────────────────────────────────────────────────────────

    /**
     * 백그라운드 스레드에서 [AestheticApiClient.predict]를 호출한다.
     * 응답이 오면 UI 스레드에서 점수와 색상을 업데이트하고 "저장" 버튼을 활성화한다.
     */
    private fun startAnalysis(file: File) {
        apiExecutor.execute {
            val score = AestheticApiClient.predict(file)
            runOnUiThread { onAnalysisResult(score) }
        }
    }

    private fun onAnalysisResult(score: Int?) {
        binding.progressBar.visibility = View.GONE
        analysisComplete = true

        if (score != null) {
            aestheticScore = score
            binding.tvScore.text = "${score}점"
            binding.tvScore.setTextColor(scoreColor(score))
        } else {
            // API 실패 — 점수 없이 저장 가능하도록 버튼은 활성화
            aestheticScore = 0
            binding.tvScore.text = "분석 실패"
            binding.tvScore.setTextColor(Color.parseColor("#AAAAAA"))
        }

        binding.btnSave.isEnabled = true
    }

    // ── 버튼 처리 ─────────────────────────────────────────────────────────────

    private fun setupButtons(tempFile: File) {
        // 저장: 갤러리에 보관 후 임시 파일 삭제
        binding.btnSave.setOnClickListener {
            val saved = AppStorage.savePhoto(this, tempFile, aestheticScore)
            tempFile.delete()

            if (saved != null) {
                Toast.makeText(this, "갤러리에 저장되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        apiExecutor.shutdown()
    }

    companion object {
        /** Intent Extra 키: 임시 캡처 파일의 절대 경로 */
        const val EXTRA_TEMP_FILE_PATH = "extra_temp_file_path"
    }
}
