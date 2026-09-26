package com.bitcomputer.hrportal.web;

import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 관리자용 직원 목록(DESIGN.md 5.2).
 *
 * <p>3단계에서는 목록만 보여 준다. 상세·생성·수정·퇴사 처리는 5단계에서 붙인다.
 * 접근 권한은 컨트롤러가 아니라 {@code SecurityConfig}의 /admin/** 규칙이 막는다.
 */
@Controller
public class AdminEmployeeController {

    private final EmployeeRepository employees;

    public AdminEmployeeController(EmployeeRepository employees) {
        this.employees = employees;
    }

    @GetMapping("/admin/employees")
    public String list(Model model) {
        model.addAttribute("employees", employees.findAllByOrderByEmpNoAsc());
        return "admin/employees";
    }
}
