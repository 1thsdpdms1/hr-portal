package com.bitcomputer.hrportal.config;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.account.AccountStatusView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 계정 상태를 DB에서 다시 확인한다(DESIGN.md 2.4, 8.1 D1).
 *
 * <p><b>왜 필요한가</b>: Spring Security는 로그인에 성공하면 사용자 정보를 세션에 복사해 두고,
 * 다음 요청부터는 그 복사본을 믿는다. 그래서 관리자가 퇴사 처리를 해도 이미 로그인한 직원은
 * 세션이 끝날 때까지 계속 들어올 수 있다. 이 필터가 요청마다 DB를 보고 그 틈을 막는다.
 *
 * <p>비용은 요청당 조회 한 번이다. 권한 검사(AuthorizationFilter)보다 먼저 실행되도록 등록한다.
 *
 * <p>이 클래스에 {@code @Component}를 붙이지 않는 이유: Spring Boot는 Filter 타입 빈을 발견하면
 * 보안 체인과 별개로 서블릿 필터 체인에도 자동 등록해서 같은 필터가 두 번 실행된다.
 * {@code SecurityConfig}에서 직접 만들어 보안 체인에만 넣는다.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;
    private final RequestMatcher jsonApiMatcher;

    public AccountStatusFilter(AccountRepository accounts, RequestMatcher jsonApiMatcher) {
        this.accounts = accounts;
        this.jsonApiMatcher = jsonApiMatcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            Optional<AccountStatusView> status = accounts.findStatusById(principal.getAccountId());

            // 계정이 지워졌거나, 비활성화됐거나, 연결 직원이 퇴사했으면 더 진행하지 않는다.
            if (status.isEmpty() || !status.get().usable()) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);

                if (jsonApiMatcher.matches(request)) {
                    response.sendError(HttpStatus.UNAUTHORIZED.value());
                } else {
                    response.sendRedirect(request.getContextPath() + "/login?expired");
                }
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
