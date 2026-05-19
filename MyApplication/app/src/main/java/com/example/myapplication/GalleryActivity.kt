package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.myapplication.databinding.ActivityGalleryBinding

/**
 * 앱 전용 갤러리 화면.
 *
 * [AppStorage.getGalleryItems]에서 저장된 사진 목록을 읽어 2열 그리드로 표시한다.
 * 각 항목에는 썸네일과 미학 점수 오버레이가 표시된다.
 * 항목을 탭하면 원본 이미지와 점수를 전체 화면 다이얼로그로 확인할 수 있다.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        loadGallery()
    }

    /** 갤러리 목록을 로드하여 RecyclerView에 연결한다. */
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

    /**
     * 선택한 사진의 원본 이미지와 미학 점수를 AlertDialog로 표시한다.
     * EXIF 회전 정보를 적용하여 올바른 방향으로 표시한다.
     *
     * @param item 선택된 [GalleryItem]
     */
    private fun showDetailDialog(item: GalleryItem) {
        val raw    = BitmapFactory.decodeFile(item.file.absolutePath)
        val bitmap = raw?.let { applyExifRotation(it, item) } ?: raw

        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        AlertDialog.Builder(this)
            .setTitle("${item.score}점")
            .setView(imageView)
            .setPositiveButton("닫기", null)
            .show()
    }

    /**
     * EXIF 회전 정보를 읽어 비트맵을 올바른 방향으로 회전한다.
     * CameraX JPEG는 픽셀을 회전하지 않고 EXIF 에만 방향을 기록하므로 표시 전 보정이 필요하다.
     */
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
