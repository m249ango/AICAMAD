package com.example.myapplication

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 전용 갤러리의 파일 입출력을 담당하는 싱글턴 유틸리티.
 *
 * ## 저장 구조
 * ```
 * (외부 앱 전용 디렉터리)/Pictures/AICAMAD_gallery/
 *   AICAMAD_20240101_120000.jpg   ← 원본 JPEG
 *   AICAMAD_20240101_120000.json  ← 메타데이터 (score, timestamp)
 * ```
 *
 * 외부 앱 전용 저장소([Context.getExternalFilesDir])를 사용하므로
 * 앱 삭제 시 모든 파일이 함께 제거된다.
 * 외부 저장소를 사용할 수 없는 경우 내부 저장소([Context.filesDir])로 폴백한다.
 */
object AppStorage {

    private const val GALLERY_DIR_NAME = "AICAMAD_gallery"
    private const val FILE_PREFIX      = "AICAMAD_"
    private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 앱 전용 갤러리 디렉터리를 반환한다. 존재하지 않으면 생성한다.
     */
    fun getGalleryDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir  // 외부 저장소 불가 시 내부 저장소 폴백
        val dir = File(base, GALLERY_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 임시 캡처 파일을 갤러리에 저장하고 메타데이터(JSON)를 함께 기록한다.
     *
     * @param context  Context
     * @param tempFile [MainActivity.takePhoto]가 생성한 임시 JPEG 파일
     * @param score    미학 점수 0~100
     * @return 저장된 JPEG 파일. 저장 실패 시 null.
     */
    fun savePhoto(context: Context, tempFile: File, score: Int): File? {
        return try {
            val timestamp = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(Date())
            val dir       = getGalleryDir(context)
            val photoFile = File(dir, "$FILE_PREFIX$timestamp.jpg")
            val metaFile  = File(dir, "$FILE_PREFIX$timestamp.json")

            // 임시 파일 → 갤러리 복사
            tempFile.copyTo(photoFile, overwrite = true)

            // 메타데이터 저장
            val meta = JSONObject().apply {
                put("score",     score)
                put("timestamp", timestamp)
            }
            metaFile.writeText(meta.toString())

            photoFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 갤러리 디렉터리의 모든 항목을 최신순으로 반환한다.
     *
     * JPEG 파일과 동일한 이름의 JSON 메타데이터 파일에서 점수를 읽는다.
     * 메타데이터 파일이 없으면 점수를 0으로 처리한다.
     */
    fun getGalleryItems(context: Context): List<GalleryItem> {
        val dir = getGalleryDir(context)
        return dir.listFiles { file -> file.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { photoFile ->
                val metaFile  = File(dir, photoFile.nameWithoutExtension + ".json")
                val score     = readScoreFromMeta(metaFile)
                val timestamp = photoFile.nameWithoutExtension.removePrefix(FILE_PREFIX)
                GalleryItem(file = photoFile, score = score, timestamp = timestamp)
            }
            ?: emptyList()
    }

    /**
     * 갤러리 항목(JPEG + JSON 쌍)을 삭제한다.
     *
     * @param item 삭제할 [GalleryItem]
     */
    fun deleteItem(item: GalleryItem) {
        item.file.delete()
        val metaFile = File(item.file.parent, item.file.nameWithoutExtension + ".json")
        metaFile.delete()
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /** JSON 메타데이터 파일에서 score 필드를 읽는다. 실패 시 0 반환. */
    private fun readScoreFromMeta(metaFile: File): Int {
        return try {
            val json = JSONObject(metaFile.readText())
            json.getInt("score")
        } catch (e: Exception) {
            0
        }
    }
}
