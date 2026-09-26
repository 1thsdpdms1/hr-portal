package com.bitcomputer.hrportal.employee;

/**
 * 관리자 기능에서 요청을 거절해야 하는 상황(DESIGN.md 5.2).
 *
 * <p>서비스는 HTTP를 모르게 두고, 상황만 구분해서 던진다. HTTP 상태코드(404/409/422)로 옮기는 일은 컨트롤러가 한다.
 */
public class EmployeeAdminException extends RuntimeException {

    public enum Reason {
        /** 대상 직원이 없다 → 404 */
        EMPLOYEE_NOT_FOUND,
        /** 로그인 ID가 이미 있다 → 409 */
        DUPLICATE_LOGIN_ID,
        /** 이미 퇴사 처리된 직원이다 → 409 */
        ALREADY_TERMINATED,
        /** 성 + 이름이 성명과 다르거나 한쪽만 비었다 → 422 */
        NAME_SPLIT_MISMATCH
    }

    private final Reason reason;

    public EmployeeAdminException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
