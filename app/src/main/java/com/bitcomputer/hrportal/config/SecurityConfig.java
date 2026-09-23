package com.bitcomputer.hrportal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * URL별 접근 규칙(DESIGN.md 5.1).
 *
 * <p>1단계에서는 "로그인 화면만 열려 있고 나머지는 모두 로그인 필요"까지만 둔다.
 * 역할별 규칙(/admin/**, /me), 계정 조회, AccountStatusFilter는 3단계에서 추가한다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // /error를 열어 두지 않으면 오류가 났을 때 오류 화면 대신 로그인 화면으로 튕긴다.
                .requestMatchers("/login", "/css/**", "/error").permitAll()
                // 목록에 없는 URL도 기본은 로그인 필요(기본 거부).
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                // 계정 없음·비밀번호 틀림·비활성·퇴사를 구분하지 않고 같은 문구를 보여 준다.
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
