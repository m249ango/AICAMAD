package com.example.myapplication

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 촬영된 이미지를 미학 점수 API로 전송하고 결과를 반환하는 클라이언트.
 *
 * ## 엔드포인트
 * API 서버 주소가 변경된 경우 [ENDPOINT] 상수를 수정한다.
 *
 * ## API 사양
 * - 메서드: POST multipart/form-data
 * - 필드명: [FIELD_NAME] (서버 사양에 따라 변경 가능)
 * - 응답: `{"score": 0.0~10.0}` (소수점 포함)
 *
 * ## 점수 변환
 * API score(0~10) × 10 → 앱 내부 score(0~100, 정수)
 */
object AestheticApiClient {

    /**
     * ⚠️ 엔드포인트 변경 시 아래 URL을 수정하세요.
     * 현재 서버는 Cloudflare Tunnel을 사용하므로 주소가 자주 변동됩니다.
     */
    private const val ENDPOINT = "https://bryant-presentations-revenues-alerts.trycloudflare.com/predict"

    /**
     * ⚠️ 서버가 기대하는 multipart 필드명.
     * 서버 사양이 바뀐 경우 이 값을 수정하세요.
     */
    private const val FIELD_NAME = "file"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // 이미지 업로드 시간 고려
        .build()

    /**
     * 이미지 파일을 API로 전송하고 미학 점수(0~100)를 반환한다.
     *
     * **백그라운드 스레드에서 호출해야 한다** — 네트워크 I/O 포함.
     *
     * @param imageFile 전송할 JPEG 이미지 파일
     * @return 미학 점수 0~100. 네트워크 오류 또는 파싱 실패 시 null.
     */
    fun predict(imageFile: File): Int? {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name     = FIELD_NAME,
                    filename = imageFile.name,
                    body     = imageFile.asRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(requestBody)
                .build()

            val responseBody = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }

            // {"score": 7.35} → 74점
            val rawScore = JSONObject(responseBody).getDouble("score")
            (rawScore * 10).roundToInt().coerceIn(0, 100)

        } catch (e: Exception) {
            null  // 네트워크 오류, 파싱 실패 등 — 호출부에서 null 처리
        }
    }
}
