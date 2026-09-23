package com.bitcomputer.hrportal.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bitcomputer.hrportal.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 1단계 확인: DB 없이 웹 계층과 보안 규칙만 띄워 검사한다.
 */
@WebMvcTest(LoginController.class)
@Import(SecurityConfig.class)
class LoginPageTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 로그인_화면은_로그인_없이_열리고_CSRF_토큰이_들어_있다() throws Exception {
        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(containsString("사내 직원 관리 시스템")));
    }

    @Test
    void 로그인하지_않고_다른_주소를_열면_로그인_화면으로_보낸다() throws Exception {
        mvc.perform(get("/me"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/employees"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void CSRF_토큰_없는_로그인_요청은_거부한다() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", "x"))
            .andExpect(status().isForbidden());
    }

    @Test
    void 계정이_없으면_로그인은_실패하고_같은_오류_화면으로_보낸다() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "nobody").param("password", "x"))
            .andExpect(redirectedUrl("/login?error"));
    }
}
