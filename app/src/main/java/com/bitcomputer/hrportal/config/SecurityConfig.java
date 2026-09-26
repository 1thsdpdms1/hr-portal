package com.bitcomputer.hrportal.config;

import com.bitcomputer.hrportal.account.AccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 인증과 URL별 접근 규칙(DESIGN.md 5.1).
 *
 * <p>권한 검사는 한곳에 모아 둔다. 컨트롤러마다 흩어 두면 새 화면을 만들 때 빠뜨리기 쉽다.
 */
@Configuration
public class SecurityConfig {

    /** 브라우저 화면이 아니라 자바스크립트가 부르는 주소. 로그인이 끊기면 로그인 화면이 아니라 401을 돌려준다. */
    private static final RequestMatcher JSON_API =
        PathPatternRequestMatcher.withDefaults().matcher("/admin/background-checks/*/status");

    @Bean
    PasswordEncoder passwordEncoder() {
        // 기본은 BCrypt. 저장 값 앞에 {bcrypt} 표시가 붙어서, 나중에 알고리즘을 바꿔도 기존 비밀번호를 읽을 수 있다.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 로그인 검사 순서를 바꾼다(DESIGN.md 5.1-6).
     *
     * <p>Spring의 기본 순서는 "계정이 비활성인지 먼저 보고 → 비밀번호 비교"다. 이러면 비활성 계정은
     * 비밀번호 계산을 건너뛰어 응답이 빨라지고, 그 시간 차이로 "이 아이디는 존재하고 비활성"임이 드러날 수 있다.
     * 그래서 사전 검사를 비우고, 상태 검사를 비밀번호 비교 뒤로 옮긴다.
     */
    @Bean
    DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
                                                        PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return provider;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountRepository accounts,
                                            AccountLoginService loginService,
                                            DaoAuthenticationProvider daoAuthenticationProvider) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // /error를 열어 두지 않으면 오류가 났을 때 오류 화면 대신 로그인 화면으로 튕긴다.
                .requestMatchers("/login", "/css/**", "/error").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/me", "/me/**").hasRole("EMPLOYEE")
                // 목록에 없는 URL도 기본은 로그인 필요(기본 거부).
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                // 계정 없음·비밀번호 틀림·비활성·퇴사를 구분하지 않고 같은 문구를 보여 준다.
                .failureUrl("/login?error")
                .successHandler(new RoleBasedSuccessHandler(loginService))
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout"))
            .authenticationProvider(daoAuthenticationProvider)
            // 역할을 검사하기 전에 계정이 아직 살아 있는지 DB로 확인한다.
            .addFilterBefore(new AccountStatusFilter(accounts, JSON_API), AuthorizationFilter.class)
            .exceptionHandling(ex -> ex
                // 상태 API는 401, 그 밖의 화면 요청은 로그인 화면으로 보낸다.
                // 두 규칙을 모두 적어야 한다. 하나만 등록하면 Spring이 그것을 "모든 요청의 기본값"으로 삼는다.
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), JSON_API)
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), AnyRequestMatcher.INSTANCE));
        return http.build();
    }
}
