package com.bitcomputer.hrportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 내 정보 수정(DESIGN.md 4.1-B, 5.2, 6.2).
 *
 * <p>확인하는 것: 바뀐 필드만 이력에 남는지, 허용하지 않은 파라미터를 거부하는지,
 * 형식 오류를 422로 돌려주는지, 수정 대상이 항상 <b>로그인한 본인</b>인지.
 */
@AutoConfigureMockMvc
class MyInfoUpdateTest extends MySqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired EmployeeChangeLogRepository changeLogs;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EntityManager em;

    private Employee emp001;
    private AccountPrincipal principal;

    @BeforeEach
    void 직원_계정을_만든다() {
        emp001 = employees.findByEmpNo("EMP-001").orElseThrow();
        Account account = accounts.save(
            Account.employee("test-emp", passwordEncoder.encode("test-password-1234"), emp001));
        accounts.flush();
        principal = new AccountPrincipal(account.getId(), account.getLoginId(), account.getPasswordHash(),
            Role.EMPLOYEE, emp001.getId(), true);
    }

    /** 테스트가 쓴 값을 앱이 DB에서 다시 읽게 한다(1차 캐시 비우기). */
    private Employee reload() {
        em.flush();
        em.clear();
        return employees.findByEmpNo("EMP-001").orElseThrow();
    }

    @Test
    void 연락처와_이메일을_수정하면_값이_바뀌고_이력이_두_줄_남는다() throws Exception {
        mvc.perform(post("/me").with(user(principal)).with(csrf())
                .param("phone", "010-1234-5678")
                .param("email", "minjun@example.com"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/me"));

        Employee saved = reload();
        assertThat(saved.getPhone()).isEqualTo("010-1234-5678");
        assertThat(saved.getEmail()).isEqualTo("minjun@example.com");

        List<EmployeeChangeLog> logs = changeLogs.findByEmployeeIdOrderByChangedAtDesc(saved.getId());
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(EmployeeChangeLog::getFieldName)
            .containsExactlyInAnyOrder("phone", "email");
        assertThat(logs).allSatisfy(log -> assertThat(log.getOldValue()).isNull());
    }

    @Test
    void 바뀐_필드만_이력에_남는다() throws Exception {
        mvc.perform(post("/me").with(user(principal)).with(csrf())
            .param("phone", "010-1234-5678").param("email", "")).andExpect(status().is3xxRedirection());
        em.flush();
        em.clear();

        // 같은 연락처를 다시 보내면 바뀐 것이 없으므로 이력이 늘지 않는다.
        mvc.perform(post("/me").with(user(principal)).with(csrf())
            .param("phone", "010-1234-5678").param("email", "")).andExpect(status().is3xxRedirection());

        List<EmployeeChangeLog> logs = changeLogs.findByEmployeeIdOrderByChangedAtDesc(reload().getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFieldName()).isEqualTo("phone");
    }

    @Test
    void 허용하지_않은_파라미터가_오면_400이고_값이_바뀌지_않는다() throws Exception {
        mvc.perform(post("/me").with(user(principal)).with(csrf())
                .param("phone", "010-1234-5678")
                .param("birthDate", "1999-01-01"))
            .andExpect(status().isBadRequest());

        Employee saved = reload();
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getBirthDate()).isEqualTo(emp001.getBirthDate());
        assertThat(changeLogs.findByEmployeeIdOrderByChangedAtDesc(saved.getId())).isEmpty();
    }

    @Test
    void 이메일_형식이_틀리면_422이고_값이_바뀌지_않는다() throws Exception {
        mvc.perform(post("/me").with(user(principal)).with(csrf())
                .param("phone", "")
                .param("email", "not-an-email"))
            .andExpect(status().isUnprocessableEntity());

        assertThat(reload().getEmail()).isNull();
    }

    @Test
    void 수정_대상은_주소가_아니라_로그인한_본인이다() throws Exception {
        Employee emp002 = employees.findByEmpNo("EMP-002").orElseThrow();

        // employeeId를 파라미터로 끼워 넣어도 허용 목록에 없으므로 거부된다.
        mvc.perform(post("/me").with(user(principal)).with(csrf())
                .param("phone", "010-9999-9999")
                .param("employeeId", String.valueOf(emp002.getId())))
            .andExpect(status().isBadRequest());

        em.flush();
        em.clear();
        assertThat(employees.findByEmpNo("EMP-002").orElseThrow().getPhone()).isNull();
    }
}
