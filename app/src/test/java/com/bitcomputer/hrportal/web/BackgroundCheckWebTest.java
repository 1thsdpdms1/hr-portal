package com.bitcomputer.hrportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.account.Role;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheckStore;
import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배경 조회 화면과 요청(DESIGN.md 5.2, 6.4).
 *
 * <p>확인하는 것: 요청은 외부 호출 없이 QUEUED 행만 만드는가, 조건이 안 맞으면 거절하는가(409·422),
 * 진행 중 중복 요청을 막는가, 민감 상세가 상태 API에 새어 나가지 않는가, 직원 계정이 접근할 수 없는가.
 *
 * <p>워커는 꺼져 있고(테스트 설정) 외부 API는 부르지 않는다. 트랜잭션을 끄고 실제로 커밋한다
 * (진행 중 1건 제한은 DB 제약이라 커밋해야 동작한다).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundCheckWebTest extends MySqlIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired BackgroundCheckStore store;

    private AccountPrincipal admin;
    private AccountPrincipal employeeUser;
    private long adminAccountId;
    private long employeeAccountId;

    @BeforeEach
    void 계정을_만든다() {
        adminAccountId = insertAdminAccount("bgcheck-web-admin");
        admin = new AccountPrincipal(adminAccountId, "bgcheck-web-admin", "{noop}test", Role.ADMIN, null, true);
        long emp001 = employeeId("EMP-001");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, employee_id) "
            + "VALUES ('bgcheck-web-emp', '{noop}test', 'EMPLOYEE', ?)", emp001);
        employeeAccountId = jdbc.queryForObject(
            "SELECT id FROM account WHERE login_id = 'bgcheck-web-emp'", Long.class);
        employeeUser = new AccountPrincipal(employeeAccountId, "bgcheck-web-emp", "{noop}test",
            Role.EMPLOYEE, emp001, true);
    }

    @AfterEach
    void 정리한다() {
        jdbc.update("DELETE FROM background_check WHERE requested_by_id IN (?, ?)",
            adminAccountId, employeeAccountId);
        jdbc.update("DELETE FROM account WHERE id IN (?, ?)", adminAccountId, employeeAccountId);
    }

    @Test
    void 조회를_요청하면_외부_호출_없이_대기_행만_만들고_상세로_보낸다() throws Exception {
        mvc.perform(post("/admin/employees/EMP-003/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/admin/background-checks/*"));

        var row = jdbc.queryForMap("SELECT b.sync_state, b.check_id, b.sent_family_name, b.sent_given_name, "
            + "b.sent_birth_date, b.post_sent_at FROM background_check b JOIN employee e ON e.id = b.employee_id "
            + "WHERE e.emp_no = 'EMP-003'");
        assertThat(row.get("sync_state")).isEqualTo("QUEUED");
        assertThat(row.get("check_id")).isNull();
        assertThat(row.get("post_sent_at")).isNull();             // 외부에 아직 아무것도 보내지 않았다
        // 보낸 값을 그대로 남긴다: 나중에 직원 정보가 바뀌어도 "무엇으로 요청했는가"를 설명할 수 있다
        assertThat(row.get("sent_family_name")).isEqualTo("남궁");
        assertThat(row.get("sent_given_name")).isEqualTo("서준");
        assertThat(row.get("sent_birth_date")).hasToString("1988-07-21");
    }

    @Test
    void 생년월일이_없는_직원은_422로_거절한다() throws Exception {
        // EMP-007은 과제 시드에서 생년월일이 확인되지 않은 직원이다.
        mvc.perform(post("/admin/employees/EMP-007/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().isUnprocessableEntity());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM background_check b "
            + "JOIN employee e ON e.id = b.employee_id WHERE e.emp_no = 'EMP-007'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void 퇴사한_직원은_409로_거절한다() throws Exception {
        jdbc.update("UPDATE employee SET employment_status='TERMINATED', terminated_at=UTC_TIMESTAMP(6) "
            + "WHERE emp_no='EMP-004'");
        try {
            mvc.perform(post("/admin/employees/EMP-004/background-checks").with(user(admin)).with(csrf()))
                .andExpect(status().isConflict());
        } finally {
            jdbc.update("UPDATE employee SET employment_status='ACTIVE', terminated_at=NULL WHERE emp_no='EMP-004'");
        }
    }

    @Test
    void 진행_중인_조회가_있으면_409로_거절한다() throws Exception {
        mvc.perform(post("/admin/employees/EMP-005/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/employees/EMP-005/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().isConflict());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM background_check b "
            + "JOIN employee e ON e.id = b.employee_id WHERE e.emp_no = 'EMP-005'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 없는_사번과_없는_조회는_404다() throws Exception {
        mvc.perform(post("/admin/employees/EMP-999/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/admin/background-checks/999999").with(user(admin)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/admin/background-checks/999999/status").with(user(admin)))
            .andExpect(status().isNotFound());
    }

    @Test
    void 상태_API는_DB만_읽고_민감_상세를_담지_않는다() throws Exception {
        long id = store.insertQueued(employeeId("EMP-006"), adminAccountId, "선우", "진", LocalDate.of(1991, 5, 5));
        jdbc.update("""
            UPDATE background_check SET sync_state='DONE', check_id='CHK-web-1', result_status='flagged',
                   criminal_record=TRUE, education_verified=TRUE, employment_verified=FALSE, credit_score='poor',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", id);

        mvc.perform(get("/admin/background-checks/" + id + "/status").with(user(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncState").value("DONE"))
            .andExpect(jsonPath("$.syncLabel").value("정상"))
            .andExpect(jsonPath("$.resultStatus").value("flagged"))
            .andExpect(jsonPath("$.resultLabel").value("추가 검토 필요"))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.criminalRecord").doesNotExist())
            .andExpect(jsonPath("$.creditScore").doesNotExist());
    }

    @Test
    void 상세_화면에서만_민감_항목을_보여_준다() throws Exception {
        long id = store.insertQueued(employeeId("EMP-008"), adminAccountId, "박", "민준", LocalDate.of(1993, 8, 17));
        jdbc.update("""
            UPDATE background_check SET sync_state='DONE', check_id='CHK-web-2', result_status='clear',
                   criminal_record=FALSE, education_verified=TRUE, employment_verified=TRUE, credit_score='good',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", id);

        mvc.perform(get("/admin/background-checks/" + id).with(user(admin)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("신용 등급")))
            .andExpect(content().string(containsString("good")))
            .andExpect(content().string(containsString("CHK-web-2")));

        // 이력 목록에는 민감 항목의 '값'이 없다(안내 문구에 항목 이름이 나오는 것과는 다르다).
        mvc.perform(get("/admin/employees/EMP-008/background-checks").with(user(admin)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("CHK-web-2")))
            .andExpect(content().string(containsString("이상 없음")))        // 결과 요약은 보여 준다
            // 신용 등급 값이 없다. 항목 '이름'은 안내 문구에 나오므로 값으로 확인한다.
            .andExpect(content().string(not(containsString("good"))))
            .andExpect(content().string(not(containsString("확인됨"))));   // 학력·경력 확인 값
    }

    @Test
    void 다시_조회와_종료는_상태가_맞을_때만_된다() throws Exception {
        long id = store.insertQueued(employeeId("EMP-009"), adminAccountId, "최", "지우", LocalDate.of(1996, 4, 3));

        // QUEUED 상태에서는 둘 다 할 수 없다
        mvc.perform(post("/admin/background-checks/" + id + "/recheck").with(user(admin)).with(csrf()))
            .andExpect(status().isConflict());
        mvc.perform(post("/admin/background-checks/" + id + "/close-unknown").with(user(admin)).with(csrf()))
            .andExpect(status().isConflict());

        jdbc.update("""
            UPDATE background_check SET sync_state='SUBMIT_UNKNOWN', post_sent_at=UTC_TIMESTAMP(6),
                   pre_list_check_ids=CAST('[]' AS JSON), next_run_at=NULL WHERE id = ?""", id);

        mvc.perform(post("/admin/background-checks/" + id + "/recheck").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT recheck_requested_at FROM background_check WHERE id = ?",
            java.sql.Timestamp.class, id)).isNotNull();

        // 종료하면 같은 직원의 새 요청이 가능해진다
        mvc.perform(post("/admin/background-checks/" + id + "/close-unknown").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT sync_state FROM background_check WHERE id = ?", String.class, id))
            .isEqualTo("SUBMIT_FAILED");
        mvc.perform(post("/admin/employees/EMP-009/background-checks").with(user(admin)).with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void 직원_계정은_배경_조회_주소에_접근할_수_없다() throws Exception {
        long id = store.insertQueued(employeeId("EMP-010"), adminAccountId, "정", "하윤", LocalDate.of(1989, 10, 11));

        mvc.perform(get("/admin/background-checks/" + id).with(user(employeeUser)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/admin/background-checks/" + id + "/status").with(user(employeeUser)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/employees/EMP-010/background-checks").with(user(employeeUser)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void 비로그인_상태_API는_로그인_화면이_아니라_401이다() throws Exception {
        mvc.perform(get("/admin/background-checks/1/status"))
            .andExpect(status().isUnauthorized());
    }
}
