package com.bitcomputer.hrportal.config;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.account.Role;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인에 성공하면 역할에 맞는 첫 화면으로 보내고 마지막 로그인 시각을 기록한다(DESIGN.md 6.1).
 *
 * <p>관리자는 "내 정보" 화면이 없으므로 직원 목록으로 간다.
 *
 * <p>이 객체 하나를 여러 요청이 함께 쓴다. 그래서 목적지를 필드에 저장했다가 쓰지 않고 요청마다 계산해 바로 넘긴다.
 * 필드에 저장하면 두 사람이 동시에 로그인할 때 서로의 목적지로 갈 수 있다.
 */
public class RoleBasedSuccessHandler implements AuthenticationSuccessHandler {

    private final AccountLoginService loginService;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public RoleBasedSuccessHandler(AccountLoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String target = "/me";
        if (authentication.getPrincipal() instanceof AccountPrincipal principal) {
            loginService.recordLogin(principal.getAccountId(), Instant.now());
            target = principal.getRole() == Role.ADMIN ? "/admin/employees" : "/me";
        }
        redirectStrategy.sendRedirect(request, response, target);
    }
}
