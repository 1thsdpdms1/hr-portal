package com.bitcomputer.hrportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 로그인, 역할별 접근 권한, 퇴사자 차단을 실제 HTTP 요청 흐름으로 확인한다(DESIGN.md 5.1, 5.2, 6.1, 6.3).
 */
@AutoConfigureMockMvc
class AccessControlTest extends MySqlIntegrationTest {

    private static final String PASSWORD = "test-password-1234";
    /** 아직 만들지 않은 주소. 보안 규칙이 컨트롤러보다 먼저 막는지 확인하는 데 쓴다. */
    private static final String STATUS_API = "/admin/background-checks/1/status";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired EmployeeRepository employees;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EntityManager em;

    private Employee emp001;

    /**
     * SQL로 직접 바꾼 값을 앱이 보게 한다.
     *
     * <p>JPA는 한 번 읽은 엔티티를 트랜잭션 안에서 기억해 두고 다시 쓴다. 그래서 테스트가 SQL로 값을 바꿔도
     * 앱은 기억해 둔 옛 값을 볼 수 있다. {@code clear()}로 기억을 비워 다음 조회가 DB를 다시 읽게 한다.
     * (운영에서는 퇴사 처리도 JPA로 하므로 이런 상황이 생기지 않는다. 테스트에서 SQL을 섞어 써서 필요한 정리다.)
     */
    private void applySql(String sql, Object... args) {
        em.flush();
        jdbc.update(sql, args);
        em.clear();
    }

    @BeforeEach
    void 계정을_만든다() {
        emp001 = employees.findByEmpNo("EMP-001").orElseThrow();
        accounts.save(Account.admin("test-admin", passwordEncoder.encode(PASSWORD)));
        accounts.save(Account.employee("test-emp", passwordEncoder.encode(PASSWORD), emp001));
        accounts.flush();
    }

    private MockHttpSession loginAs(String loginId, String expectedTarget) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user(loginId).password(PASSWORD))
            .andExpect(redirectedUrl(expectedTarget))
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void 로그인하지_않으면_화면은_로그인으로_보내고_상태_API는_401을_준다() throws Exception {
        mvc.perform(get("/me")).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/employees")).andExpect(redirectedUrlPattern("**/login"));
        // 자바스크립트가 부르는 주소는 로그인 화면 HTML 대신 401을 받아야 상태를 구분할 수 있다.
        mvc.perform(get(STATUS_API)).andExpect(status().isUnauthorized());
    }

    @Test
    void 직원은_자기_정보만_보고_관리자_화면은_403이다() throws Exception {
        MockHttpSession session = loginAs("test-emp", "/me");

        mvc.perform(get("/me").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("EMP-001")));
        mvc.perform(get("/admin/employees").session(session)).andExpect(status().isForbidden());
        mvc.perform(get(STATUS_API).session(session)).andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_직원_목록을_보고_내_정보_화면은_403이다() throws Exception {
        MockHttpSession session = loginAs("test-admin", "/admin/employees");

        mvc.perform(get("/admin/employees").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("남궁서준")));
        mvc.perform(get("/me").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void 로그인에_성공하면_마지막_로그인_시각을_기록한다() throws Exception {
        loginAs("test-emp", "/me");

        Long lagSeconds = jdbc.queryForObject(
            "SELECT ABS(TIMESTAMPDIFF(SECOND, last_login_at, UTC_TIMESTAMP(6))) FROM account WHERE login_id = 'test-emp'",
            Long.class);
        assertThat(lagSeconds).isNotNull().isLessThan(5);
    }

    @Test
    void 비밀번호가_틀리거나_비활성_계정이면_같은_곳으로_보낸다() throws Exception {
        mvc.perform(formLogin("/login").user("test-emp").password("wrong"))
            .andExpect(redirectedUrl("/login?error"));
        mvc.perform(formLogin("/login").user("no-such-user").password(PASSWORD))
            .andExpect(redirectedUrl("/login?error"));

        applySql("UPDATE account SET is_active = FALSE WHERE login_id = 'test-emp'");
        // 비밀번호는 맞지만 계정이 비활성이다. 실패 결과가 같아야 "그 아이디는 존재한다"가 드러나지 않는다.
        mvc.perform(formLogin("/login").user("test-emp").password(PASSWORD))
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void 퇴사한_직원은_계정이_살아_있어도_로그인할_수_없다() throws Exception {
        applySql("UPDATE employee SET employment_status = 'TERMINATED', terminated_at = UTC_TIMESTAMP(6) WHERE id = ?",
            emp001.getId());

        mvc.perform(formLogin("/login").user("test-emp").password(PASSWORD))
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void 이미_로그인한_직원도_계정을_끄면_다음_요청에서_막힌다() throws Exception {
        MockHttpSession session = loginAs("test-emp", "/me");
        mvc.perform(get("/me").session(session)).andExpect(status().isOk());

        applySql("UPDATE account SET is_active = FALSE, deactivated_at = UTC_TIMESTAMP(6) WHERE login_id = 'test-emp'");

        mvc.perform(get("/me").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).as("세션도 함께 끊어야 한다").isTrue();
    }

    @Test
    void 이미_로그인한_직원도_퇴사_처리하면_다음_요청에서_막힌다() throws Exception {
        MockHttpSession session = loginAs("test-emp", "/me");

        // 5단계의 퇴사 처리는 계정 비활성화까지 한 트랜잭션에서 하지만,
        // 직원 상태만 바뀐 경우에도 막히는지 따로 확인한다(검사를 두 겹으로 둔 이유).
        applySql("UPDATE employee SET employment_status = 'TERMINATED', terminated_at = UTC_TIMESTAMP(6) WHERE id = ?",
            emp001.getId());

        mvc.perform(get("/me").session(session)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void 퇴사자의_상태_API_요청은_401을_받는다() throws Exception {
        MockHttpSession session = loginAs("test-admin", "/admin/employees");
        applySql("UPDATE account SET is_active = FALSE WHERE login_id = 'test-admin'");

        mvc.perform(get(STATUS_API).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_세션이_끝난다() throws Exception {
        MockHttpSession session = loginAs("test-emp", "/me");

        // 로그아웃은 POST + CSRF 토큰이 필요하다(GET으로는 되지 않는다).
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }
}
