package com.example.myapplication

import java.io.File

/**
 * 앱 전용 갤러리에 보관된 사진 한 장을 나타내는 데이터 클래스.
 *
 * @param file          JPEG 이미지 파일 (앱 전용 저장소 내 위치)
 * @param score         미학 점수 0~100 (MUSIQ API score × 10 변환값)
 * @param category      구도 카테고리 이름 (예: "삼등분 법칙", "대각선")
 * @param categoryScore 카테고리 확신도 0~100 (LandscapeClassifier 확률 × 100)
 * @param mode          촬영 모드 ("사물" or "풍경")
 * @param timestamp     촬영 시각 문자열 (파일명에서 추출, 예: "20240101_120000")
 */
data class GalleryItem(
    val file:          File,
    val score:         Int,
    val category:      String,
    val categoryScore: Int,
    val mode:          String,
    val timestamp:     String
)
