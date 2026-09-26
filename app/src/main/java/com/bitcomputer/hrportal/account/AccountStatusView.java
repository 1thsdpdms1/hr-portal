package com.bitcomputer.hrportal.account;

import com.bitcomputer.hrportal.employee.EmploymentStatus;

/**
 * 요청마다 확인하는 계정 상태. 엔티티 대신 필요한 값 세 개만 한 번의 조회로 읽는다.
 *
 * @param active            계정 사용 가능 여부
 * @param role              역할
 * @param employmentStatus  연결된 직원의 재직 상태. 관리자 계정은 null.
 */
public record AccountStatusView(boolean active, Role role, EmploymentStatus employmentStatus) {

    /** 직원 계정은 계정이 살아 있고 그 직원이 재직 중일 때만 쓸 수 있다. */
    public boolean usable() {
        if (!active) {
            return false;
        }
        if (role == Role.EMPLOYEE) {
            return employmentStatus == EmploymentStatus.ACTIVE;
        }
        return true;
    }
}
