package com.example.myapplication

/**
 * 사용자가 선택 가능한 구도(Composition) 유형.
 *
 * [기여] 구도 기반 실시간 가이드 모드 신규 도입.
 * PopupMenu에서 [displayName]을 항목 텍스트로 사용한다.
 *
 * @param displayName 팝업 메뉴에 표시할 한국어 이름
 */
enum class Composition(val displayName: String) {
    /** 중앙 구도: 피사체를 화면 정중앙에 배치 */
    CENTER("중앙"),

    /** 황금비 구도: 황금비(0.382/0.618) 4개 교차점 중 가장 가까운 지점 활용 */
    GOLDEN_RATIO("황금비"),

    /** 삼분할 구도: 1/3·2/3 등분선 4개 교차점 중 가장 가까운 지점 활용 */
    RULE_OF_THIRDS("삼분할")
}
