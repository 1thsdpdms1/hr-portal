package com.bitcomputer.hrportal.config;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출용 계정(관리자 1개, 직원 1개)을 앱 시작 때 만든다(DESIGN.md 7.1, 7.3).
 *
 * <p>계정을 마이그레이션 SQL이 아니라 여기서 만드는 이유: 비밀번호 해시를 저장소에 올리지 않기 위해서다.
 * 비밀번호는 환경변수로만 받는다. 환경변수가 없으면 계정을 만들지 않고 경고만 남긴다.
 * 기본 비밀번호를 코드에 두면 배포 후에도 그대로 남아 누구나 로그인할 수 있게 된다.
 *
 * <p>이미 있는 로그인 ID는 건드리지 않는다. 그래서 앱을 여러 번 켜도 비밀번호가 되돌아가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "seed.accounts.enabled", havingValue = "true", matchIfMissing = true)
public class SeedAccountsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedAccountsRunner.class);

    private final AccountRepository accounts;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.accounts.admin-login-id:admin}")
    private String adminLoginId;

    @Value("${seed.accounts.employee-login-id:emp001}")
    private String employeeLoginId;

    @Value("${seed.accounts.employee-emp-no:EMP-001}")
    private String employeeEmpNo;

    @Value("${SEED_ADMIN_PASSWORD:}")
    private String adminPassword;

    @Value("${SEED_EMPLOYEE_PASSWORD:}")
    private String employeePassword;

    public SeedAccountsRunner(AccountRepository accounts, EmployeeRepository employees, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createAdminIfMissing();
        createEmployeeIfMissing();
    }

    private void createAdminIfMissing() {
        String loginId = Account.normalizeLoginId(adminLoginId);
        if (accounts.existsByLoginId(loginId)) {
            return;
        }
        if (adminPassword.isBlank()) {
            log.warn("환경변수 SEED_ADMIN_PASSWORD가 없어 관리자 계정({})을 만들지 않았다.", loginId);
            return;
        }
        accounts.save(Account.admin(loginId, passwordEncoder.encode(adminPassword)));
        log.info("관리자 계정({})을 만들었다.", loginId);
    }

    private void createEmployeeIfMissing() {
        String loginId = Account.normalizeLoginId(employeeLoginId);
        if (accounts.existsByLoginId(loginId)) {
            return;
        }
        if (employeePassword.isBlank()) {
            log.warn("환경변수 SEED_EMPLOYEE_PASSWORD가 없어 직원 계정({})을 만들지 않았다.", loginId);
            return;
        }
        Employee employee = employees.findByEmpNo(employeeEmpNo).orElse(null);
        if (employee == null) {
            log.warn("사번 {} 직원이 없어 직원 계정({})을 만들지 않았다.", employeeEmpNo, loginId);
            return;
        }
        accounts.save(Account.employee(loginId, passwordEncoder.encode(employeePassword), employee));
        log.info("직원 계정({} → {})을 만들었다.", loginId, employeeEmpNo);
    }
}
