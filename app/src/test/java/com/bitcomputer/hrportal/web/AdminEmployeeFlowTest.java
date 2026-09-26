package com.bitcomputer.hrportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.account.Role;
import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeChangeLog;
import com.bitcomputer.hrportal.employee.EmployeeChangeLogRepository;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import com.bitcomputer.hrportal.employee.EmploymentStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 기능(DESIGN.md 5.2, 6.3): 직원 생성, 신원 수정, 퇴사 처리.
 *
 * <p>확인하는 것: 사번 자동 발급, 로그인 계정 동시 생성, 로그인 ID 중복 거절, EMP-007 생년월일 입력,
 * 성 + 이름 불일치 거절, 퇴사 처리와 계정 비활성화, 두 번째 퇴사 요청 거절, 없는 사번 404.
 */
@AutoConfigureMockMvc
class AdminEmployeeFlowTest extends MySqlIntegrationTest {

    private static final String PASSWORD = "test-password-1234";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired EmployeeChangeLogRepository changeLogs;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EntityManager em;

    private AccountPrincipal admin;

    @BeforeEach
    void 관리자_계정을_만든다() {
        Account account = accounts.save(Account.admin("test-admin", passwordEncoder.encode(PASSWORD)));
        accounts.flush();
        admin = new AccountPrincipal(account.getId(), account.getLoginId(), account.getPasswordHash(),
            Role.ADMIN, null, true);
    }

    private void clearPersistenceContext() {
        em.flush();
        em.clear();
    }

    @Test
    void 직원을_만들면_사번이_발급되고_계정도_함께_생긴다() throws Exception {
        mvc.perform(post("/admin/employees").with(user(admin)).with(csrf())
                .param("nameKo", "한지민").param("familyNameKo", "한").param("givenNameKo", "지민")
                .param("birthDate", "1997-06-01")
                .param("loginId", "emp011").param("password", "new-password-1234"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/employees/EMP-011"));

        clearPersistenceContext();
        Employee created = employees.findByEmpNo("EMP-011").orElseThrow();
        assertThat(created.getNameKo()).isEqualTo("한지민");
        assertThat(created.getBirthDate()).isEqualTo(LocalDate.of(1997, 6, 1));

        Account account = accounts.findByLoginId("emp011").orElseThrow();
        assertThat(account.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(account.getEmployee().getId()).isEqualTo(created.getId());
        // 비밀번호는 해시로만 저장한다.
        assertThat(account.getPasswordHash()).doesNotContain("new-password-1234");
    }

    @Test
    void 로그인_ID가_중복이면_409이고_직원도_만들어지지_않는다() throws Exception {
        accounts.saveAndFlush(Account.admin("emp011", passwordEncoder.encode(PASSWORD)));
        clearPersistenceContext();

        mvc.perform(post("/admin/employees").with(user(admin)).with(csrf())
                .param("nameKo", "한지민").param("familyNameKo", "한").param("givenNameKo", "지민")
                .param("loginId", "emp011").param("password", "new-password-1234"))
            .andExpect(status().isConflict());

        clearPersistenceContext();
        assertThat(employees.findByEmpNo("EMP-011")).isEmpty();
    }

    @Test
    void 성과_이름을_합친_값이_성명과_다르면_422다() throws Exception {
        mvc.perform(post("/admin/employees").with(user(admin)).with(csrf())
                .param("nameKo", "한지민").param("familyNameKo", "김").param("givenNameKo", "지민")
                .param("loginId", "emp011").param("password", "new-password-1234"))
            .andExpect(status().isUnprocessableEntity());

        clearPersistenceContext();
        assertThat(employees.findByEmpNo("EMP-011")).isEmpty();
    }

    @Test
    void EMP_007의_생년월일을_입력하면_저장되고_이력이_남는다() throws Exception {
        Employee emp007 = employees.findByEmpNo("EMP-007").orElseThrow();
        assertThat(emp007.getBirthDate()).isNull();

        mvc.perform(post("/admin/employees/EMP-007/edit").with(user(admin)).with(csrf())
                .param("nameKo", emp007.getNameKo())
                .param("familyNameKo", emp007.getFamilyNameKo())
                .param("givenNameKo", emp007.getGivenNameKo())
                .param("birthDate", "1995-11-20"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/employees/EMP-007"));

        clearPersistenceContext();
        Employee saved = employees.findByEmpNo("EMP-007").orElseThrow();
        assertThat(saved.getBirthDate()).isEqualTo(LocalDate.of(1995, 11, 20));

        List<EmployeeChangeLog> logs = changeLogs.findByEmployeeIdOrderByChangedAtDesc(saved.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFieldName()).isEqualTo("birth_date");
        assertThat(logs.get(0).getOldValue()).isNull();
        assertThat(logs.get(0).getNewValue()).isEqualTo("1995-11-20");
    }

    @Test
    void 퇴사_처리하면_상태와_계정이_함께_바뀌고_이력이_남는다() throws Exception {
        Employee emp001 = employees.findByEmpNo("EMP-001").orElseThrow();
        accounts.saveAndFlush(Account.employee("test-emp", passwordEncoder.encode(PASSWORD), emp001));
        clearPersistenceContext();

        mvc.perform(post("/admin/employees/EMP-001/terminate").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/employees/EMP-001"));

        clearPersistenceContext();
        Employee terminated = employees.findByEmpNo("EMP-001").orElseThrow();
        assertThat(terminated.getEmploymentStatus()).isEqualTo(EmploymentStatus.TERMINATED);
        assertThat(terminated.getTerminatedAt()).isNotNull();
        assertThat(accounts.findByLoginId("test-emp").orElseThrow().isActive()).isFalse();

        List<EmployeeChangeLog> logs = changeLogs.findByEmployeeIdOrderByChangedAtDesc(terminated.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFieldName()).isEqualTo("employment_status");
        assertThat(logs.get(0).getNewValue()).isEqualTo("TERMINATED");
    }

    @Test
    void 이미_퇴사한_직원을_다시_퇴사_처리하면_409다() throws Exception {
        mvc.perform(post("/admin/employees/EMP-002/terminate").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection());
        clearPersistenceContext();

        mvc.perform(post("/admin/employees/EMP-002/terminate").with(user(admin)).with(csrf()))
            .andExpect(status().isConflict());
    }

    @Test
    void 없는_사번은_상세도_수정도_퇴사도_404다() throws Exception {
        mvc.perform(get("/admin/employees/EMP-999").with(user(admin)))
            .andExpect(status().isNotFound());
        mvc.perform(post("/admin/employees/EMP-999/terminate").with(user(admin)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/admin/employees/EMP-999/edit").with(user(admin)).with(csrf())
                .param("nameKo", "없는사람"))
            .andExpect(status().isNotFound());
    }

    @Test
    void 직원_계정은_관리자_주소에_접근할_수_없다() throws Exception {
        Employee emp001 = employees.findByEmpNo("EMP-001").orElseThrow();
        Account account = accounts.saveAndFlush(
            Account.employee("test-emp", passwordEncoder.encode(PASSWORD), emp001));
        AccountPrincipal employeePrincipal = new AccountPrincipal(account.getId(), account.getLoginId(),
            account.getPasswordHash(), Role.EMPLOYEE, emp001.getId(), true);
        clearPersistenceContext();

        mvc.perform(get("/admin/employees/new").with(user(employeePrincipal)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/employees/EMP-001/terminate").with(user(employeePrincipal)).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
