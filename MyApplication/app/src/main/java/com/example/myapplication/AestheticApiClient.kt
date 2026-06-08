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

object AestheticApiClient {

    private const val ENDPOINT   = "https://mas-persistent-syndication-horse.trycloudflare.com/predict"
    private const val FIELD_NAME = "file"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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
            null
        }
    }
}
