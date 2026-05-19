package com.example.myapplication

import android.graphics.BitmapFactory
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
     *
     * @param item 선택된 [GalleryItem]
     */
    private fun showDetailDialog(item: GalleryItem) {
        val bitmap = BitmapFactory.decodeFile(item.file.absolutePath)

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
}
