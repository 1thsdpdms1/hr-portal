package com.bitcomputer.hrportal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.account.Role;
import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 시드 계정 생성 규칙 확인(DESIGN.md 7.3).
 *
 * <p>테스트 설정에서는 이 작업이 꺼져 있으므로(seed.accounts.enabled=false) 직접 만들어 실행한다.
 * 덕분에 테스트가 끝나면 롤백되어 테스트 DB에 계정이 남지 않는다.
 */
class SeedAccountsRunnerTest extends MySqlIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired PasswordEncoder passwordEncoder;

    private SeedAccountsRunner runner(String adminPassword, String employeePassword) {
        SeedAccountsRunner runner = new SeedAccountsRunner(accounts, employees, passwordEncoder);
        ReflectionTestUtils.setField(runner, "adminLoginId", "seed-admin");
        ReflectionTestUtils.setField(runner, "employeeLoginId", "seed-emp");
        ReflectionTestUtils.setField(runner, "employeeEmpNo", "EMP-001");
        ReflectionTestUtils.setField(runner, "adminPassword", adminPassword);
        ReflectionTestUtils.setField(runner, "employeePassword", employeePassword);
        return runner;
    }

    @Test
    void 환경변수가_있으면_관리자와_직원_계정을_만든다() {
        runner("admin-pw-1234", "emp-pw-1234").run(null);
        accounts.flush();

        Account admin = accounts.findByLoginId("seed-admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getEmployee()).as("관리자 계정은 직원과 연결하지 않는다").isNull();
        // 평문이 아니라 해시로 저장되고, 어떤 방식으로 만들었는지 앞에 표시가 붙는다.
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("admin-pw-1234");
        assertThat(passwordEncoder.matches("admin-pw-1234", admin.getPasswordHash())).isTrue();

        Account employee = accounts.findByLoginId("seed-emp").orElseThrow();
        assertThat(employee.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(employee.getEmployee().getEmpNo()).isEqualTo("EMP-001");
    }

    @Test
    void 환경변수가_없으면_계정을_만들지_않는다() {
        runner("", "").run(null);
        accounts.flush();

        assertThat(accounts.findByLoginId("seed-admin")).isEmpty();
        assertThat(accounts.findByLoginId("seed-emp")).isEmpty();
    }

    @Test
    void 이미_있는_계정의_비밀번호는_덮어쓰지_않는다() {
        accounts.saveAndFlush(Account.admin("seed-admin", passwordEncoder.encode("원래-비밀번호")));

        runner("새-비밀번호", "").run(null);
        accounts.flush();

        Account admin = accounts.findByLoginId("seed-admin").orElseThrow();
        assertThat(passwordEncoder.matches("원래-비밀번호", admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("새-비밀번호", admin.getPasswordHash())).isFalse();
    }
}
