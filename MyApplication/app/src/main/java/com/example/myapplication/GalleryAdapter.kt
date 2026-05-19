package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 앱 전용 갤러리의 사진 목록을 2열 그리드로 표시하는 RecyclerView 어댑터.
 *
 * 각 항목은 썸네일 이미지와 우하단 점수 오버레이로 구성된다.
 * 점수 색상은 구간별로 다르게 표시된다:
 * - 0~59점: 빨강 (#FF5252)
 * - 60~79점: 노랑 (#FFD740)
 * - 80~100점: 초록 (#69F0AE)
 *
 * @param items      표시할 [GalleryItem] 목록 (최신순 정렬 권장)
 * @param onItemClick 항목 클릭 콜백 — [GalleryActivity]에서 상세 보기에 사용
 */
class GalleryAdapter(
    private val items: List<GalleryItem>,
    private val onItemClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    // ── ViewHolder ─────────────────────────────────────────────────────────────

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvItemScore: TextView  = view.findViewById(R.id.tvItemScore)
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 썸네일: 메모리 절약을 위해 작은 크기로 샘플링
        val bitmap = decodeSampledBitmap(item, targetSize = 400)
        holder.ivThumbnail.setImageBitmap(bitmap)

        // 점수 오버레이
        holder.tvItemScore.text = "${item.score}점"
        holder.tvItemScore.setTextColor(scoreColor(item.score))

        // 클릭
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * 파일에서 [targetSize] 픽셀 이하로 샘플링하여 비트맵을 디코딩한다.
     * 전체 해상도 원본을 한꺼번에 메모리에 올리지 않아 OOM을 방지한다.
     * EXIF 회전 정보를 적용하여 항상 올바른 방향으로 반환한다.
     */
    private fun decodeSampledBitmap(item: GalleryItem, targetSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.absolutePath, opts)

        var sampleSize = 1
        while (opts.outWidth / sampleSize > targetSize) sampleSize *= 2

        val raw = BitmapFactory.decodeFile(
            item.file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        return applyExifRotation(raw, item.file)
    }

    /**
     * EXIF 회전 정보를 읽어 비트맵을 올바른 방향으로 회전한다.
     * CameraX JPEG는 픽셀을 회전하지 않고 EXIF 에만 방향을 기록하므로 표시 전 보정이 필요하다.
     */
    private fun applyExifRotation(bitmap: Bitmap, file: java.io.File): Bitmap {
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

    /** 점수 구간별 색상 */
    private fun scoreColor(score: Int) = when {
        score < 60 -> Color.parseColor("#FF5252")
        score < 80 -> Color.parseColor("#FFD740")
        else       -> Color.parseColor("#69F0AE")
    }
}
