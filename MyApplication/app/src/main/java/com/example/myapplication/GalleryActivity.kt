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

        // edge-to-edge 보정: Material3 테마가 자동으로 edge-to-edge 를 활성화한다.
        // RecyclerView 에 내비게이션 바 높이만큼 하단 패딩을 추가하여 마지막 항목이 가려지지 않게 한다.
        // clipToPadding=false 로 스크롤 중에도 패딩 영역이 투명하게 유지된다.
        binding.rvGallery.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvGallery) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

        loadGallery()
    }

    /**
     * [AppStorage.getGalleryItems]에서 저장된 사진 목록을 읽어 RecyclerView에 연결한다.
     *
     * 항목이 없으면 빈 상태 안내 텍스트([binding.tvEmpty])를 표시하고
     * RecyclerView를 숨긴다. 항목이 있으면 2열 그리드 레이아웃으로 표시한다.
     */
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
     * 선택한 사진의 원본 이미지와 4가지 메타데이터를 AlertDialog로 표시한다.
     * EXIF 회전 정보를 적용하여 올바른 방향으로 표시한다.
     *
     * @param item 선택된 [GalleryItem]
     */
    private fun showDetailDialog(item: GalleryItem) {
        val raw    = BitmapFactory.decodeFile(item.file.absolutePath)
        val bitmap = raw?.let { applyExifRotation(it, item) } ?: raw

        // 이미지 + 메타데이터를 수직으로 배치하는 레이아웃
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        // 이미지
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        container.addView(imageView)

        // 메타데이터 텍스트 (미학 점수 · 카테고리 · 촬영 모드)
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
