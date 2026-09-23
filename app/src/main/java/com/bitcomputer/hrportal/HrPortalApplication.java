package com.bitcomputer.hrportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 사내 직원 관리 시스템 진입점.
 *
 * <p>UserDetailsServiceAutoConfiguration을 끄는 이유: 켜 두면 Spring Boot가 "user"라는 임시 계정을 만들고
 * 비밀번호를 로그에 찍는다. 이 앱의 계정은 DB의 account 테이블에서만 온다(DESIGN.md 4.2, 3단계에서 구현).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class HrPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrPortalApplication.class, args);
    }
}
