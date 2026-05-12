package com.example.myapplication

/**
 * 피사체 중심이 가이드 박스와 얼마나 일치하는지를 나타내는 상태.
 *
 * [기여] 구도 모드 신규 도입. GuideOverlayView에서 색상을 결정하는 기준으로 사용된다.
 */
enum class MatchState {
    /** 피사체가 가이드 박스 밖에 있는 상태 (흰색 박스 표시) */
    IDLE,

    /** 피사체 중심이 가이드 박스 안에 진입 — 2초 유지 타이머 진행 중 (초록 박스 + 펄스) */
    MATCHED,

    /** 2초 연속 일치 완료 — 촬영을 권장하는 상태 (밝은 초록 실선 + "지금 촬영하세요!" 라벨) */
    RECOMMEND
}
