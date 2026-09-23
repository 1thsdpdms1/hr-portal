package com.bitcomputer.hrportal.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면만 보여 준다. 로그인 처리(POST /login)는 Spring Security가 한다.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
