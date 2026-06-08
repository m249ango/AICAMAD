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

class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding

    private var aestheticScore: Int = 0
    private var category: String = "—"
    private var categoryScore: Int = 0
    private var mode: String = "—"

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tempPath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH)
        if (tempPath == null) {
            Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mode = intent.getStringExtra(EXTRA_MODE) ?: "—"

        val tempFile = File(tempPath)

        // edge-to-edge: 내비게이션 바 높이만큼 scrollContent 하단 패딩 추가
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollContent) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

        binding.tvMode.text = mode
        setupImagePreview(tempFile)
        startAnalysis(tempFile)
        setupButtons(tempFile)
    }

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

            // ② 미학 점수 API 호출
            val apiScore = AestheticApiClient.predict(file)

            runOnUiThread { onAnalysisComplete(apiScore, landscapeResult) }
        }
    }

    private fun onAnalysisComplete(apiScore: Int?, landscapeResult: LandscapeResult?) {
        binding.progressBar.visibility = View.GONE

        if (apiScore != null) {
            aestheticScore = apiScore
            binding.tvScore.text = "${apiScore}점"
            binding.tvScore.setTextColor(scoreColor(apiScore))
        } else {
            aestheticScore = 0
            binding.tvScore.text = "분석 실패"
            binding.tvScore.setTextColor(Color.parseColor("#AAAAAA"))
        }

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

    private fun setupButtons(tempFile: File) {
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

        binding.btnDiscard.setOnClickListener {
            tempFile.delete()
            finish()
        }
    }

    private fun scoreColor(score: Int) = when {
        score < 60 -> Color.parseColor("#FF5252")
        score < 80 -> Color.parseColor("#FFD740")
        else       -> Color.parseColor("#69F0AE")
    }

    // CameraX JPEG는 픽셀을 회전하지 않고 EXIF에만 방향을 기록
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

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    companion object {
        const val EXTRA_TEMP_FILE_PATH = "extra_temp_file_path"
        const val EXTRA_MODE           = "extra_mode"
    }
}
