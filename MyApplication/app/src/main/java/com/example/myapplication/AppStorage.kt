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
 *   AICAMAD_20240101_120000.json  ← 메타데이터 (4가지)
 * ```
 *
 * ## JSON 메타데이터 스키마
 * ```json
 * {
 *   "score":          74,          // 미학 점수 0~100
 *   "category":       "삼등분 법칙", // 구도 카테고리
 *   "category_score": 87,          // 카테고리 확신도 0~100
 *   "mode":           "사물",       // 촬영 모드 ("사물" or "풍경")
 *   "timestamp":      "20240101_120000"
 * }
 * ```
 *
 * 외부 앱 전용 저장소([Context.getExternalFilesDir])를 사용하므로
 * 앱 삭제 시 모든 파일이 함께 제거된다.
 * 외부 저장소를 사용할 수 없는 경우 내부 저장소([Context.filesDir])로 폴백한다.
 */
object AppStorage {

    /** 갤러리 파일을 저장할 하위 디렉터리 이름. 앱 전용 저장소 내에 생성된다. */
    private const val GALLERY_DIR_NAME = "AICAMAD_gallery"

    /**
     * 저장 파일명 앞에 붙는 접두사.
     * 갤러리 디렉터리에 다른 파일이 혼입되더라도 이 앱의 파일임을 구분할 수 있다.
     */
    private const val FILE_PREFIX      = "AICAMAD_"

    /**
     * 파일명에 포함될 타임스탬프 형식 (예: "20240101_120000").
     * [Locale.US]를 사용하여 기기 언어 설정과 무관하게 일정한 파일명을 생성한다.
     */
    private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    // JSON 필드명 상수 — JSON 키를 한 곳에서 관리하여 오타를 방지한다.
    private const val KEY_SCORE          = "score"
    private const val KEY_CATEGORY       = "category"
    private const val KEY_CATEGORY_SCORE = "category_score"
    private const val KEY_MODE           = "mode"
    private const val KEY_TIMESTAMP      = "timestamp"

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
     * @param context       Context
     * @param tempFile      [MainActivity.takePhoto]가 생성한 임시 JPEG 파일
     * @param score         미학 점수 0~100
     * @param category      구도 카테고리 이름 (예: "삼등분 법칙")
     * @param categoryScore 카테고리 확신도 0~100
     * @param mode          촬영 모드 ("사물" or "풍경")
     * @return 저장된 JPEG 파일. 저장 실패 시 null.
     */
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

            // 임시 파일 → 갤러리 복사
            tempFile.copyTo(photoFile, overwrite = true)

            // 메타데이터 저장 (4가지 항목)
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

    /**
     * 갤러리 디렉터리의 모든 항목을 최신순으로 반환한다.
     *
     * JPEG 파일과 동일한 이름의 JSON 메타데이터 파일에서 모든 필드를 읽는다.
     * 이전 버전에서 저장된 파일처럼 일부 필드가 없으면 기본값("—", 0)으로 처리한다.
     */
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

    /**
     * JSON 파일에서 읽은 메타데이터를 담는 내부 데이터 클래스.
     * 기본값이 설정되어 있어 이전 버전에서 저장된 파일처럼 일부 필드가 없어도
     * [readMeta]에서 기본값으로 안전하게 처리된다 (하위 호환성 보장).
     */
    private data class Meta(
        val score:         Int    = 0,
        val category:      String = "—",
        val categoryScore: Int    = 0,
        val mode:          String = "—"
    )

    /**
     * JSON 메타데이터 파일을 파싱한다.
     * 파일이 없거나 특정 필드가 없는 경우 기본값으로 처리한다 (하위 호환성 보장).
     */
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
            Meta()  // 파일 없거나 파싱 실패 → 전부 기본값
        }
    }
}
