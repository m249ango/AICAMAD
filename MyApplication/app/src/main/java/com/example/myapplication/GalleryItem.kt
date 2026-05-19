package com.example.myapplication

import java.io.File

/**
 * 앱 전용 갤러리에 보관된 사진 한 장을 나타내는 데이터 클래스.
 *
 * @param file      JPEG 이미지 파일 (앱 전용 저장소 내 위치)
 * @param score     미학 점수 0~100 (API 응답 score × 10 변환값)
 * @param timestamp 촬영 시각 문자열 (파일명에서 추출, 예: "20240101_120000")
 */
data class GalleryItem(
    val file:      File,
    val score:     Int,
    val timestamp: String
)
