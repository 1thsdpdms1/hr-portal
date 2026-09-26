package com.bitcomputer.hrportal.bgcheck;

/**
 * 조회 요청을 거절해야 하는 상황(DESIGN.md 5.2).
 * 서비스는 HTTP를 모르고 상황만 구분한다. 상태코드로 옮기는 일은 컨트롤러가 한다.
 */
public class BackgroundCheckRequestException extends RuntimeException {

    public enum Reason {
        /** 404 */
        EMPLOYEE_NOT_FOUND,
        /** 404 */
        CHECK_NOT_FOUND,
        /** 409 — 퇴사자 */
        EMPLOYEE_TERMINATED,
        /** 409 — 진행 중이거나 확인 필요한 조회가 이미 있다 */
        ALREADY_ACTIVE,
        /** 409 — 지금 상태에서는 할 수 없는 동작(다시 조회·종료) */
        NOT_APPLICABLE,
        /** 422 — 생년월일 미확인 */
        BIRTH_DATE_UNKNOWN,
        /** 422 — 성/이름이 분리되어 있지 않다 */
        NAME_NOT_SPLIT
    }

    private final Reason reason;

    public BackgroundCheckRequestException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
