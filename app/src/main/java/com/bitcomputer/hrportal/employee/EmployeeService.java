package com.bitcomputer.hrportal.employee;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 본인이 하는 변경(DESIGN.md 6.2).
 *
 * <p>승인 절차 없이 즉시 반영하기로 했으므로(판단 D2), 잘못된 수정을 되짚을 수단은 변경 이력뿐이다.
 * 그래서 값 갱신과 이력 기록을 <b>한 트랜잭션</b>에서 한다. 중간에 실패하면 둘 다 되돌아간다.
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final EmployeeChangeRecorder changeRecorder;

    public EmployeeService(EmployeeRepository employees, EmployeeChangeRecorder changeRecorder) {
        this.employees = employees;
        this.changeRecorder = changeRecorder;
    }

    /**
     * 연락처·이메일 수정. 바뀐 필드만 이력에 남긴다.
     *
     * @return 실제로 바뀐 필드가 하나라도 있으면 true
     */
    @Transactional
    public boolean updateContact(Long employeeId, String phone, String email, Long actorAccountId) {
        Employee employee = employees.findById(employeeId)
            .orElseThrow(() -> new IllegalStateException("직원을 찾을 수 없다: id=" + employeeId));

        String oldPhone = employee.getPhone();
        String oldEmail = employee.getEmail();
        employee.changeContact(phone, email);

        boolean phoneChanged = changeRecorder.recordIfChanged(
            employee, actorAccountId, EmployeeChangeRecorder.FIELD_PHONE, oldPhone, employee.getPhone());
        boolean emailChanged = changeRecorder.recordIfChanged(
            employee, actorAccountId, EmployeeChangeRecorder.FIELD_EMAIL, oldEmail, employee.getEmail());
        return phoneChanged || emailChanged;
    }
}
