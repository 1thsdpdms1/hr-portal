package com.bitcomputer.hrportal.web;

import com.bitcomputer.hrportal.account.AccountPrincipal;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheckRequestException;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheckService;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheckViews;
import com.bitcomputer.hrportal.bgcheck.BgCheckProperties;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자용 Background Check 화면과 요청(DESIGN.md 5.2, 6.4).
 *
 * <p>여기서는 <b>외부 API를 부르지 않는다.</b> 요청은 DB에 할 일을 적어 두고 바로 응답하며,
 * 실제 호출은 워커가 한다. 상태 조회도 DB만 읽는다.
 *
 * <p>민감 상세(범죄기록·신용등급 등)는 상세 화면에서만 보여 준다. 목록과 상태 JSON에는 담기지 않는다
 * (타입 자체에 필드가 없다 — {@link BackgroundCheckViews}).
 *
 * <p>직원 본인용 주소는 만들지 않는다. 배경 조회 결과는 관리자만 본다.
 */
@Controller
public class BackgroundCheckController {

    private final BackgroundCheckService service;
    private final EmployeeRepository employees;
    private final BgCheckProperties properties;

    public BackgroundCheckController(BackgroundCheckService service, EmployeeRepository employees,
                                     BgCheckProperties properties) {
        this.service = service;
        this.employees = employees;
        this.properties = properties;
    }

    /** 새 조회 요청. QUEUED 행만 만들고 상세 화면으로 보낸다. */
    @PostMapping("/admin/employees/{empNo}/background-checks")
    public String request(@PathVariable("empNo") String empNo,
                          @AuthenticationPrincipal AccountPrincipal principal,
                          RedirectAttributes redirect) {
        long id;
        try {
            id = service.request(empNo, principal.getAccountId());
        } catch (BackgroundCheckRequestException e) {
            throw toResponseStatus(e);
        }
        redirect.addFlashAttribute("message", "배경 조회를 요청했습니다. 결과는 이 화면에서 확인할 수 있습니다.");
        return "redirect:/admin/background-checks/" + id;
    }

    /** 직원별 조회 이력. 외부 목록 API가 아니라 우리 DB 기록을 보여 준다(실측: 목록 API 200은 7회 중 1회). */
    @GetMapping("/admin/employees/{empNo}/background-checks")
    public String history(@PathVariable("empNo") String empNo, Model model) {
        Employee employee = employees.findByEmpNo(empNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("employee", employee);
        model.addAttribute("checks", service.findHistory(employee.getId()));
        return "admin/employee-checks";
    }

    @GetMapping("/admin/background-checks/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        BackgroundCheckViews.Detail detail = service.findDetail(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("check", detail);
        // 키가 없으면 워커가 외부 호출을 하지 않아 요청이 '요청 대기'에서 멈춘다. 화면에서 이유를 알려 준다.
        model.addAttribute("candidateKeyMissing", !properties.hasCandidateKey());
        return "admin/background-check";
    }

    /**
     * 화면 자동 갱신용 상태(JSON). 읽기 전용이며 민감 상세를 넣지 않는다.
     * 로그인이 끊기면 로그인 화면으로 보내지 않고 401을 돌려준다(SecurityConfig의 JSON_API 규칙).
     */
    @GetMapping("/admin/background-checks/{id}/status")
    @ResponseBody
    public ResponseEntity<BackgroundCheckViews.Status> status(@PathVariable("id") long id) {
        return service.findStatus(id)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 다시 조회. 워커가 다음 tick에 외부 호출 1회만 한다. */
    @PostMapping("/admin/background-checks/{id}/recheck")
    public String recheck(@PathVariable("id") long id, RedirectAttributes redirect) {
        try {
            service.requestRecheck(id);
        } catch (BackgroundCheckRequestException e) {
            throw toResponseStatus(e);
        }
        redirect.addFlashAttribute("message", "다시 조회를 요청했습니다. 잠시 후 결과가 갱신됩니다.");
        return "redirect:/admin/background-checks/" + id;
    }

    /** 생성 안 됨으로 종료. 이 처리 뒤에야 같은 직원의 새 요청이 가능하다. */
    @PostMapping("/admin/background-checks/{id}/close-unknown")
    public String closeUnknown(@PathVariable("id") long id, RedirectAttributes redirect) {
        try {
            service.closeAsNotCreated(id);
        } catch (BackgroundCheckRequestException e) {
            throw toResponseStatus(e);
        }
        redirect.addFlashAttribute("message", "이 요청을 '생성 안 됨'으로 종료했습니다. 새 조회를 요청할 수 있습니다.");
        return "redirect:/admin/background-checks/" + id;
    }

    private ResponseStatusException toResponseStatus(BackgroundCheckRequestException e) {
        HttpStatus status = switch (e.getReason()) {
            case EMPLOYEE_NOT_FOUND, CHECK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EMPLOYEE_TERMINATED, ALREADY_ACTIVE, NOT_APPLICABLE -> HttpStatus.CONFLICT;
            case BIRTH_DATE_UNKNOWN, NAME_NOT_SPLIT -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return new ResponseStatusException(status, e.getMessage());
    }
}
