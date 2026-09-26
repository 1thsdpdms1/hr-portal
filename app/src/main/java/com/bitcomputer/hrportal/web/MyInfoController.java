package com.bitcomputer.hrportal.web;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 직원 본인의 "내 정보"(DESIGN.md 5.2, 6.2).
 *
 * <p>3단계에서는 조회만 한다. 수정 폼과 변경 이력은 4단계에서 붙인다.
 *
 * <p>보여 줄 직원은 <b>로그인 정보(세션)에서만</b> 정한다. 주소에 사번을 받지 않으므로
 * 다른 사람 사번을 넣어 남의 정보를 보는 일이 생길 수 없다.
 */
@Controller
public class MyInfoController {

    private final EmployeeRepository employees;

    public MyInfoController(EmployeeRepository employees) {
        this.employees = employees;
    }

    @GetMapping("/me")
    public String myInfo(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        Employee employee = employees.findById(principal.getEmployeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("employee", employee);
        return "me";
    }
}
