package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.example.myapplication.databinding.ActivityGalleryBinding

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // edge-to-edge: 내비게이션 바 높이만큼 하단 패딩 추가
        binding.rvGallery.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvGallery) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

        loadGallery()
    }

    private fun loadGallery() {
        val items = AppStorage.getGalleryItems(this)

        if (items.isEmpty()) {
            binding.rvGallery.visibility = View.GONE
            binding.tvEmpty.visibility   = View.VISIBLE
            return
        }

        binding.tvEmpty.visibility   = View.GONE
        binding.rvGallery.visibility = View.VISIBLE

        binding.rvGallery.layoutManager = GridLayoutManager(this, 2)
        binding.rvGallery.adapter = GalleryAdapter(items) { item ->
            showDetailDialog(item)
        }
    }

    private fun showDetailDialog(item: GalleryItem) {
        val raw    = BitmapFactory.decodeFile(item.file.absolutePath)
        val bitmap = raw?.let { applyExifRotation(it, item) } ?: raw

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        container.addView(imageView)

        val dp = resources.displayMetrics.density
        val metaText = android.widget.TextView(this).apply {
            text = buildString {
                append("미학 점수    ${item.score}점\n")
                append("카테고리     ${item.category} : ${item.categoryScore}점\n")
                append("촬영 모드    ${item.mode}")
            }
            textSize  = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
            setPadding(
                (16 * dp).toInt(), (12 * dp).toInt(),
                (16 * dp).toInt(), (12 * dp).toInt()
            )
        }
        container.addView(metaText)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("닫기", null)
            .show()
    }

    // CameraX JPEG는 픽셀을 회전하지 않고 EXIF에만 방향을 기록
    private fun applyExifRotation(bitmap: Bitmap, item: GalleryItem): Bitmap {
        val degrees = when (
            ExifInterface(item.file.absolutePath)
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
}
