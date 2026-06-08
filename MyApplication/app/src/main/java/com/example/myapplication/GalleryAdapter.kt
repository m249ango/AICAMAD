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

class GalleryAdapter(
    private val items: List<GalleryItem>,
    private val onItemClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvItemScore: TextView  = view.findViewById(R.id.tvItemScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ivThumbnail.setImageBitmap(decodeSampledBitmap(item, targetSize = 400))
        holder.tvItemScore.text = "${item.score}점"
        holder.tvItemScore.setTextColor(scoreColor(item.score))
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    // OOM 방지: targetSize 이하로 다운샘플링 후 디코딩
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

    // CameraX JPEG는 픽셀을 회전하지 않고 EXIF에만 방향을 기록
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

    private fun scoreColor(score: Int) = when {
        score < 60 -> Color.parseColor("#FF5252")
        score < 80 -> Color.parseColor("#FFD740")
        else       -> Color.parseColor("#69F0AE")
    }
}
