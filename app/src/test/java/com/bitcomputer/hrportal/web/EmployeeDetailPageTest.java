package com.bitcomputer.hrportal.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.account.Role;
import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 이력이 있는 직원의 상세 화면이 실제로 그려지는지 확인한다.
 *
 * <p>왜 트랜잭션을 끄고 하는가: 이 앱은 {@code open-in-view: false}라 <b>화면을 그릴 때 DB 세션이 이미 닫혀 있다.</b>
 * 그런데 다른 통합 테스트는 {@code @Transactional}이라 테스트가 끝날 때까지 세션이 열려 있어, 지연 로딩이
 * 운영에서만 실패하는 문제를 잡지 못한다(실제로 상세 화면이 500으로 떴다).
 * 그래서 이 테스트만 트랜잭션 없이 돌리고, 넣은 행은 {@code @AfterEach}에서 직접 지운다.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmployeeDetailPageTest extends MySqlIntegrationTest {

    private static final String ADMIN_LOGIN_ID = "detail-page-admin";
    private static final String TARGET_EMP_NO = "EMP-009";

    @Autowired MockMvc mvc;

    private AccountPrincipal admin;
    private long adminAccountId;
    private long targetEmployeeId;

    @BeforeEach
    void 이력이_있는_직원을_준비한다() {
        adminAccountId = insertAdminAccount(ADMIN_LOGIN_ID);
        targetEmployeeId = employeeId(TARGET_EMP_NO);
        jdbc.update("INSERT INTO employee_change_log (employee_id, changed_by_id, field_name, old_value, new_value) "
            + "VALUES (?, ?, 'phone', NULL, '010-1111-2222')", targetEmployeeId, adminAccountId);
        admin = new AccountPrincipal(adminAccountId, ADMIN_LOGIN_ID, "{noop}test", Role.ADMIN, null, true);
    }

    @AfterEach
    void 넣은_행을_지운다() {
        jdbc.update("DELETE FROM employee_change_log WHERE employee_id = ?", targetEmployeeId);
        jdbc.update("DELETE FROM account WHERE id = ?", adminAccountId);
    }

    @Test
    void 변경_이력이_있어도_상세_화면이_그려지고_변경자가_보인다() throws Exception {
        mvc.perform(get("/admin/employees/" + TARGET_EMP_NO).with(user(admin)))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(ADMIN_LOGIN_ID)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("010-1111-2222")));
    }
}
