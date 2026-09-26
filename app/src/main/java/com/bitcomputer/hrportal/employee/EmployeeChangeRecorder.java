package com.bitcomputer.hrportal.employee;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 직원 정보가 바뀔 때 {@code employee_change_log}에 한 줄씩 남긴다(DESIGN.md 4.4, 6.2).
 *
 * <p>본인 수정(4단계)과 관리자 수정·퇴사 처리(5단계)가 같은 방식으로 기록해야 해서 한곳에 모았다.
 * {@code field_name}은 DB CHECK(ck_change_log_field_name)가 허용하는 값만 쓸 수 있으므로 상수로 둔다.
 */
@Component
public class EmployeeChangeRecorder {

    public static final String FIELD_PHONE = "phone";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_BIRTH_DATE = "birth_date";
    public static final String FIELD_NAME_KO = "name_ko";
    public static final String FIELD_FAMILY_NAME_KO = "family_name_ko";
    public static final String FIELD_GIVEN_NAME_KO = "given_name_ko";
    public static final String FIELD_EMPLOYMENT_STATUS = "employment_status";

    private final EmployeeChangeLogRepository changeLogs;
    private final AccountRepository accounts;

    public EmployeeChangeRecorder(EmployeeChangeLogRepository changeLogs, AccountRepository accounts) {
        this.changeLogs = changeLogs;
        this.accounts = accounts;
    }

    /**
     * 값이 실제로 바뀐 경우에만 기록한다. 바뀌지 않았으면 아무것도 하지 않고 false를 돌려준다.
     * 호출한 쪽은 이 값으로 "바뀐 필드가 하나라도 있는지"를 판단한다.
     */
    public boolean recordIfChanged(Employee employee, Long actorAccountId, String fieldName,
                                   Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return false;
        }
        // getReferenceById: 계정 전체를 읽지 않고 외래키만 채운다(로그인한 계정이라 존재가 보장된다).
        Account actor = accounts.getReferenceById(actorAccountId);
        changeLogs.save(new EmployeeChangeLog(employee, actor, fieldName, asText(oldValue), asText(newValue)));
        return true;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        return value.toString();
    }
}
