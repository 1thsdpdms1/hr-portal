package com.bitcomputer.hrportal.web;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeAdminException;
import com.bitcomputer.hrportal.employee.EmployeeAdminService;
import com.bitcomputer.hrportal.employee.EmployeeChangeLogRepository;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자용 직원 관리(DESIGN.md 5.2, 6.3): 목록, 상세, 생성, 신원 수정, 퇴사 처리.
 *
 * <p>접근 권한은 컨트롤러가 아니라 {@code SecurityConfig}의 {@code /admin/**} 규칙이 막는다.
 * 여기서 역할을 다시 검사하면 규칙이 두 곳에 흩어져 한쪽만 고치는 실수가 생긴다.
 *
 * <p>Background Check 요청·이력 화면은 6단계에서 상세 화면에 붙인다.
 */
@Controller
public class AdminEmployeeController {

    private final EmployeeRepository employees;
    private final EmployeeChangeLogRepository changeLogs;
    private final EmployeeAdminService adminService;

    public AdminEmployeeController(EmployeeRepository employees, EmployeeChangeLogRepository changeLogs,
                                   EmployeeAdminService adminService) {
        this.employees = employees;
        this.changeLogs = changeLogs;
        this.adminService = adminService;
    }

    @GetMapping("/admin/employees")
    public String list(Model model) {
        model.addAttribute("employees", employees.findAllByOrderByEmpNoAsc());
        return "admin/employees";
    }

    @GetMapping("/admin/employees/new")
    public String newForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new NewEmployeeForm());
        }
        return "admin/employee-new";
    }

    @PostMapping("/admin/employees")
    public Object create(@Valid @ModelAttribute("form") NewEmployeeForm form, BindingResult binding,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return new ModelAndView("admin/employee-new", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Employee created;
        try {
            created = adminService.create(form.getNameKo(), form.getFamilyNameKo(), form.getGivenNameKo(),
                form.getBirthDate(), form.getPhone(), form.getEmail(), form.getLoginId(), form.getPassword());
        } catch (EmployeeAdminException e) {
            return handleAdminException(e, "admin/employee-new");
        }
        redirect.addFlashAttribute("message", created.getEmpNo() + " 직원을 만들었습니다.");
        return "redirect:/admin/employees/" + created.getEmpNo();
    }

    @GetMapping("/admin/employees/{empNo}")
    public String detail(@PathVariable String empNo, Model model) {
        Employee employee = employees.findByEmpNo(empNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("employee", employee);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new EmployeeIdentityForm(employee.getNameKo(), employee.getFamilyNameKo(),
                employee.getGivenNameKo(), employee.getBirthDate(), employee.getPhone(), employee.getEmail()));
        }
        // 이력에도 개인정보가 들어가므로 관리자 화면에서만 보여 준다(DESIGN.md 4.4).
        model.addAttribute("changeLogs", changeLogs.findByEmployeeIdOrderByChangedAtDesc(employee.getId()));
        return "admin/employee-detail";
    }

    @PostMapping("/admin/employees/{empNo}/edit")
    public Object edit(@PathVariable String empNo, @AuthenticationPrincipal AccountPrincipal principal,
                       @Valid @ModelAttribute("form") EmployeeIdentityForm form, BindingResult binding,
                       Model model, RedirectAttributes redirect) {
        Employee employee = employees.findByEmpNo(empNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (binding.hasErrors()) {
            return detailWithStatus(employee, model, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            adminService.updateIdentity(empNo, form.getNameKo(), form.getFamilyNameKo(), form.getGivenNameKo(),
                form.getBirthDate(), form.getPhone(), form.getEmail(), principal.getAccountId());
        } catch (EmployeeAdminException e) {
            if (e.getReason() == EmployeeAdminException.Reason.NAME_SPLIT_MISMATCH) {
                binding.reject("nameSplit", e.getMessage());
                return detailWithStatus(employee, model, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return handleAdminException(e, null);
        }
        redirect.addFlashAttribute("message", "직원 정보를 수정했습니다.");
        return "redirect:/admin/employees/" + empNo;
    }

    @PostMapping("/admin/employees/{empNo}/terminate")
    public String terminate(@PathVariable String empNo, @AuthenticationPrincipal AccountPrincipal principal,
                            RedirectAttributes redirect) {
        try {
            adminService.terminate(empNo, principal.getAccountId());
        } catch (EmployeeAdminException e) {
            throw toResponseStatusException(e);
        }
        redirect.addFlashAttribute("message", empNo + " 직원을 퇴사 처리했습니다. 로그인 계정도 함께 막혔습니다.");
        return "redirect:/admin/employees/" + empNo;
    }

    /** 상세 화면을 특정 HTTP 상태로 다시 그린다(폼 오류를 보여 줄 때 사용). */
    private ModelAndView detailWithStatus(Employee employee, Model model, HttpStatus status) {
        ModelAndView mav = new ModelAndView("admin/employee-detail", status);
        mav.addAllObjects(model.asMap());
        mav.addObject("employee", employee);
        mav.addObject("changeLogs", changeLogs.findByEmployeeIdOrderByChangedAtDesc(employee.getId()));
        return mav;
    }

    private Object handleAdminException(EmployeeAdminException e, String viewOnUnprocessable) {
        if (viewOnUnprocessable != null
            && (e.getReason() == EmployeeAdminException.Reason.NAME_SPLIT_MISMATCH
                || e.getReason() == EmployeeAdminException.Reason.DUPLICATE_LOGIN_ID)) {
            // 사용자가 고칠 수 있는 입력 문제는 폼으로 돌려보낸다. 로그인 ID 중복은 409로 알린다.
            HttpStatus status = e.getReason() == EmployeeAdminException.Reason.DUPLICATE_LOGIN_ID
                ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
            ModelAndView mav = new ModelAndView(viewOnUnprocessable, status);
            mav.addObject("errorMessage", e.getMessage());
            return mav;
        }
        throw toResponseStatusException(e);
    }

    private ResponseStatusException toResponseStatusException(EmployeeAdminException e) {
        HttpStatus status = switch (e.getReason()) {
            case EMPLOYEE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_LOGIN_ID, ALREADY_TERMINATED -> HttpStatus.CONFLICT;
            case NAME_SPLIT_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return new ResponseStatusException(status, e.getMessage());
    }
}
