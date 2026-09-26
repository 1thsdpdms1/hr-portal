package com.bitcomputer.hrportal.web;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import com.bitcomputer.hrportal.employee.EmployeeService;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 직원 본인의 "내 정보"(DESIGN.md 5.2, 6.2).
 *
 * <p>보여 주고 고칠 직원은 <b>로그인 정보(세션)에서만</b> 정한다. 주소에 사번을 받지 않으므로
 * 다른 사람 사번을 넣어 남의 정보를 보거나 고치는 일이 생길 수 없다.
 */
@Controller
public class MyInfoController {

    /** 이 요청에서 받을 파라미터는 이게 전부다. CSRF 토큰은 Spring Security가 쓰는 값이라 함께 허용한다. */
    private static final Set<String> ALLOWED_PARAMS = Set.of("phone", "email", "_csrf");

    private final EmployeeRepository employees;
    private final EmployeeService employeeService;

    public MyInfoController(EmployeeRepository employees, EmployeeService employeeService) {
        this.employees = employees;
        this.employeeService = employeeService;
    }

    @GetMapping("/me")
    public String myInfo(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        Employee employee = loadMyEmployee(principal);
        model.addAttribute("employee", employee);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new MyInfoForm(employee.getPhone(), employee.getEmail()));
        }
        return "me";
    }

    /**
     * 연락처·이메일 수정.
     *
     * <p>허용하지 않은 파라미터가 오면 조용히 무시하지 않고 400으로 거부한다(DESIGN.md 5.2).
     * 무시하면 "생년월일도 보냈는데 반영됐겠지"라고 오해할 수 있고, 폼을 바꿨을 때 실수를 늦게 발견한다.
     */
    @PostMapping("/me")
    public Object updateMyInfo(@AuthenticationPrincipal AccountPrincipal principal,
                               @RequestParam java.util.Map<String, String> params,
                               @Valid @ModelAttribute("form") MyInfoForm form,
                               BindingResult binding,
                               RedirectAttributes redirect) {
        rejectUnknownParams(params.keySet());
        Employee employee = loadMyEmployee(principal);

        if (binding.hasErrors()) {
            // 입력 형식 오류는 422(내용은 이해했지만 처리할 수 없음)로 돌려주고 폼을 다시 보여 준다.
            ModelAndView mav = new ModelAndView("me", HttpStatus.UNPROCESSABLE_ENTITY);
            mav.addObject("employee", employee);
            return mav;
        }

        boolean changed = employeeService.updateContact(
            employee.getId(), form.getPhone(), form.getEmail(), principal.getAccountId());
        redirect.addFlashAttribute("message", changed ? "내 정보를 수정했습니다." : "바뀐 내용이 없습니다.");
        // POST 후 리다이렉트: 새로고침으로 같은 수정이 다시 전송되지 않게 한다.
        return "redirect:/me";
    }

    private Employee loadMyEmployee(AccountPrincipal principal) {
        Long employeeId = principal.getEmployeeId();
        if (employeeId == null) {
            // 직원과 연결되지 않은 계정(관리자)은 URL 규칙에서 이미 막히지만, 방어적으로 한 번 더 막는다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return employees.findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void rejectUnknownParams(Set<String> names) {
        for (String name : names) {
            if (!ALLOWED_PARAMS.contains(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용하지 않은 파라미터: " + name);
            }
        }
    }
}
