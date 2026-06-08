package com.example.myapplication

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppStorage {

    private const val GALLERY_DIR_NAME = "AICAMAD_gallery"
    private const val FILE_PREFIX      = "AICAMAD_"
    private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    private const val KEY_SCORE          = "score"
    private const val KEY_CATEGORY       = "category"
    private const val KEY_CATEGORY_SCORE = "category_score"
    private const val KEY_MODE           = "mode"
    private const val KEY_TIMESTAMP      = "timestamp"

    fun getGalleryDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val dir = File(base, GALLERY_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun savePhoto(
        context:       Context,
        tempFile:      File,
        score:         Int,
        category:      String,
        categoryScore: Int,
        mode:          String
    ): File? {
        return try {
            val timestamp = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date())
            val dir       = getGalleryDir(context)
            val photoFile = File(dir, "$FILE_PREFIX$timestamp.jpg")
            val metaFile  = File(dir, "$FILE_PREFIX$timestamp.json")

            tempFile.copyTo(photoFile, overwrite = true)

            val meta = JSONObject().apply {
                put(KEY_SCORE,          score)
                put(KEY_CATEGORY,       category)
                put(KEY_CATEGORY_SCORE, categoryScore)
                put(KEY_MODE,           mode)
                put(KEY_TIMESTAMP,      timestamp)
            }
            metaFile.writeText(meta.toString())

            photoFile
        } catch (e: Exception) {
            null
        }
    }

    fun getGalleryItems(context: Context): List<GalleryItem> {
        val dir = getGalleryDir(context)
        return dir.listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { photoFile ->
                val metaFile  = File(dir, photoFile.nameWithoutExtension + ".json")
                val meta      = readMeta(metaFile)
                val timestamp = photoFile.nameWithoutExtension.removePrefix(FILE_PREFIX)
                GalleryItem(
                    file          = photoFile,
                    score         = meta.score,
                    category      = meta.category,
                    categoryScore = meta.categoryScore,
                    mode          = meta.mode,
                    timestamp     = timestamp
                )
            }
            ?: emptyList()
    }

    fun deleteItem(item: GalleryItem) {
        item.file.delete()
        val metaFile = File(item.file.parent, item.file.nameWithoutExtension + ".json")
        metaFile.delete()
    }

    private data class Meta(
        val score:         Int    = 0,
        val category:      String = "—",
        val categoryScore: Int    = 0,
        val mode:          String = "—"
    )

    private fun readMeta(metaFile: File): Meta {
        return try {
            val json = JSONObject(metaFile.readText())
            Meta(
                score         = json.optInt(KEY_SCORE, 0),
                category      = json.optString(KEY_CATEGORY, "—"),
                categoryScore = json.optInt(KEY_CATEGORY_SCORE, 0),
                mode          = json.optString(KEY_MODE, "—")
            )
        } catch (e: Exception) {
            Meta()
        }
    }
}
