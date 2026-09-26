package com.bitcomputer.hrportal.employee;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.employee.EmployeeAdminException.Reason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자가 하는 직원 변경: 생성, 신원 수정, 퇴사 처리(DESIGN.md 5.2, 6.3).
 *
 * <p>모든 변경은 {@code employee_change_log}에 이력을 남기고, 값 갱신과 이력 기록을 한 트랜잭션에서 한다.
 */
@Service
public class EmployeeAdminService {

    /** 사번 형식. 새 사번은 "현재 최대 번호 + 1"로 만든다(DESIGN.md 4.1). */
    private static final Pattern EMP_NO = Pattern.compile("^EMP-(\\d+)$");
    private static final String EMP_NO_FORMAT = "EMP-%03d";

    private final EmployeeRepository employees;
    private final AccountRepository accounts;
    private final EmployeeChangeRecorder changeRecorder;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public EmployeeAdminService(EmployeeRepository employees, AccountRepository accounts,
                                EmployeeChangeRecorder changeRecorder, PasswordEncoder passwordEncoder,
                                TransactionTemplate transactionTemplate) {
        this.employees = employees;
        this.accounts = accounts;
        this.changeRecorder = changeRecorder;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 직원과 로그인 계정을 함께 만든다. 둘 중 하나만 생기면 로그인할 수 없는 직원이나 주인 없는 계정이 남으므로
     * 한 트랜잭션에서 만든다.
     *
     * <p>사번은 서버가 발급한다. 두 관리자가 동시에 만들면 같은 번호를 계산할 수 있는데, 그때는 DB의 UNIQUE가
     * 막는다. 막히면 번호를 다시 계산해 <b>한 번</b> 더 시도한다. 재시도는 새 트랜잭션에서 해야 해서
     * {@link TransactionTemplate}으로 트랜잭션 경계를 직접 잡는다(같은 클래스의 메서드를 호출하면 트랜잭션이 새로 시작되지 않는다).
     */
    public Employee create(String nameKo, String familyNameKo, String givenNameKo, LocalDate birthDate,
                           String phone, String email, String loginId, String rawPassword) {
        String normalizedLoginId = Account.normalizeLoginId(loginId);
        if (accounts.existsByLoginId(normalizedLoginId)) {
            throw new EmployeeAdminException(Reason.DUPLICATE_LOGIN_ID, "이미 있는 로그인 ID: " + normalizedLoginId);
        }
        try {
            return transactionTemplate.execute(status ->
                insert(nameKo, familyNameKo, givenNameKo, birthDate, phone, email, normalizedLoginId, rawPassword));
        } catch (DataIntegrityViolationException e) {
            if (isLoginIdConflict(e)) {
                throw new EmployeeAdminException(Reason.DUPLICATE_LOGIN_ID, "이미 있는 로그인 ID: " + normalizedLoginId);
            }
            // 사번이 겹친 경우: 다른 관리자가 먼저 같은 번호를 썼다. 번호를 다시 계산해 한 번만 재시도한다.
            return transactionTemplate.execute(status ->
                insert(nameKo, familyNameKo, givenNameKo, birthDate, phone, email, normalizedLoginId, rawPassword));
        }
    }

    private Employee insert(String nameKo, String familyNameKo, String givenNameKo, LocalDate birthDate,
                            String phone, String email, String normalizedLoginId, String rawPassword) {
        String name = trimToNull(nameKo);
        String family = trimToNull(familyNameKo);
        String given = trimToNull(givenNameKo);
        validateNameSplit(name, family, given);

        Employee employee = new Employee(nextEmpNo(), name, family, given, birthDate);
        employee.changeContact(phone, email);
        employees.saveAndFlush(employee);

        accounts.saveAndFlush(
            Account.employee(normalizedLoginId, passwordEncoder.encode(rawPassword), employee));
        return employee;
    }

    private String nextEmpNo() {
        int max = employees.findMaxEmpNo()
            .map(EMP_NO::matcher)
            .filter(Matcher::matches)
            .map(matcher -> Integer.parseInt(matcher.group(1)))
            .orElse(0);
        return String.format(EMP_NO_FORMAT, max + 1);
    }

    /** 신원·연락 정보 수정. 바뀐 필드만 이력에 남긴다. */
    @Transactional
    public void updateIdentity(String empNo, String nameKo, String familyNameKo, String givenNameKo,
                               LocalDate birthDate, String phone, String email, Long actorAccountId) {
        Employee employee = employees.findByEmpNo(empNo)
            .orElseThrow(() -> new EmployeeAdminException(Reason.EMPLOYEE_NOT_FOUND, "없는 사번: " + empNo));

        String name = trimToNull(nameKo);
        String family = trimToNull(familyNameKo);
        String given = trimToNull(givenNameKo);
        validateNameSplit(name, family, given);

        String oldName = employee.getNameKo();
        String oldFamily = employee.getFamilyNameKo();
        String oldGiven = employee.getGivenNameKo();
        LocalDate oldBirthDate = employee.getBirthDate();
        String oldPhone = employee.getPhone();
        String oldEmail = employee.getEmail();

        employee.changeIdentity(name, family, given, birthDate);
        employee.changeContact(phone, email);

        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_NAME_KO, oldName, employee.getNameKo());
        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_FAMILY_NAME_KO, oldFamily, employee.getFamilyNameKo());
        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_GIVEN_NAME_KO, oldGiven, employee.getGivenNameKo());
        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_BIRTH_DATE, oldBirthDate, employee.getBirthDate());
        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_PHONE, oldPhone, employee.getPhone());
        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_EMAIL, oldEmail, employee.getEmail());
    }

    /**
     * 퇴사 처리(DESIGN.md 6.3, 판단 D1: 즉시 차단).
     *
     * <p>직원 상태 변경과 계정 비활성화를 한 트랜잭션에서 한다. 둘이 따로 움직이면
     * "퇴사했는데 로그인되는" 상태가 생긴다. 이미 퇴사한 직원이면 409로 거절한다.
     *
     * <p>(6단계 예정) 보존 기간 정책을 정하면 이 직원의 Background Check 민감 상세를 함께 지운다.
     */
    @Transactional
    public void terminate(String empNo, Long actorAccountId) {
        Employee employee = employees.findByEmpNoForUpdate(empNo)
            .orElseThrow(() -> new EmployeeAdminException(Reason.EMPLOYEE_NOT_FOUND, "없는 사번: " + empNo));
        if (!employee.isActive()) {
            throw new EmployeeAdminException(Reason.ALREADY_TERMINATED, "이미 퇴사 처리된 직원: " + empNo);
        }

        Instant now = Instant.now();
        EmploymentStatus oldStatus = employee.getEmploymentStatus();
        employee.terminate(now);
        accounts.findByEmployeeId(employee.getId()).ifPresent(account -> account.deactivate(now));

        // DECISIONS.md (3): 이 자리에서 배경 조회의 민감 상세(범죄경력·신용점수·검증 결과)를 NULL로 지우고
        // background_check.detail_purged_at 에 파기 시각을 남기기로 정했다. 되돌릴 수 없는 동작이라 이번 범위에서는 넣지 않았다.

        changeRecorder.recordIfChanged(employee, actorAccountId,
            EmployeeChangeRecorder.FIELD_EMPLOYMENT_STATUS, oldStatus, employee.getEmploymentStatus());
    }

    /**
     * 성·이름은 둘 다 있거나 둘 다 없어야 하고, 합치면 성명과 같아야 한다.
     * 최종 보장은 DB CHECK(ck_employee_name_split)가 하지만, 오류를 화면에서 설명하려면 여기서 먼저 막아야 한다.
     */
    private void validateNameSplit(String nameKo, String familyNameKo, String givenNameKo) {
        if (familyNameKo == null && givenNameKo == null) {
            return;
        }
        if (familyNameKo == null || givenNameKo == null) {
            throw new EmployeeAdminException(Reason.NAME_SPLIT_MISMATCH, "성과 이름은 둘 다 입력하거나 둘 다 비워야 합니다.");
        }
        if (!(familyNameKo + givenNameKo).equals(nameKo)) {
            throw new EmployeeAdminException(Reason.NAME_SPLIT_MISMATCH, "성 + 이름이 성명과 같아야 합니다.");
        }
    }

    private static boolean isLoginIdConflict(DataIntegrityViolationException e) {
        String message = Optional.ofNullable(e.getMostSpecificCause().getMessage()).orElse("");
        return message.contains("uq_account_login_id");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
