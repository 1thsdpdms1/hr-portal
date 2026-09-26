package com.bitcomputer.hrportal.employee;

import java.time.Instant;

/**
 * 화면에 내려보내는 변경 이력 한 줄(DESIGN.md 4.4).
 *
 * <p>엔티티를 그대로 넘기지 않는 이유: {@code changedBy}는 지연 로딩이고 이 앱은 {@code open-in-view: false}라
 * 화면을 그리는 시점에는 DB 세션이 닫혀 있다. 템플릿에서 {@code log.changedBy.loginId}를 읽으면 그때 500이 난다.
 * 필요한 값(변경자 로그인 ID)만 조회 시점에 함께 읽어 담는다. 계정 엔티티 전체가 화면으로 나가지 않는 이점도 있다.
 */
public record EmployeeChangeLogView(
    Instant changedAt,
    String fieldName,
    String oldValue,
    String newValue,
    String changedByLoginId) {
}
