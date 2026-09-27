package com.bitcomputer.hrportal.bgcheck;

import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 관리자의 Background Check 요청과 화면 조회(DESIGN.md 5.2, 6.4).
 *
 * <p><b>이 클래스는 외부 API를 부르지 않는다.</b> 요청은 {@code QUEUED} 행을 INSERT만 하고 바로 끝난다.
 * 실제 호출은 워커가 한다. 실측에서 정상 GET도 최대 29.7초, 순차 GET의 59.1%가 실패였기 때문에
 * 화면 요청 안에서 기다리면 관리자 화면이 멈춘다(MEASUREMENTS.md 1·2-4).
 */
@Service
public class BackgroundCheckService {

    private final EmployeeRepository employees;
    private final BackgroundCheckRepository checks;
    private final BackgroundCheckStore store;

    public BackgroundCheckService(EmployeeRepository employees, BackgroundCheckRepository checks,
                                  BackgroundCheckStore store) {
        this.employees = employees;
        this.checks = checks;
        this.store = store;
    }

    /**
     * 새 조회 요청. 보낼 값이 갖춰졌는지 먼저 확인하고 {@code QUEUED} 행을 넣는다.
     *
     * @return 만들어진 조회 ID
     */
    public long request(String empNo, Long actorAccountId) {
        Employee employee = employees.findByEmpNo(empNo)
            .orElseThrow(() -> new BackgroundCheckRequestException(
                BackgroundCheckRequestException.Reason.EMPLOYEE_NOT_FOUND, "없는 사번: " + empNo));
        if (!employee.isActive()) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.EMPLOYEE_TERMINATED,
                "퇴사한 직원은 배경 조회를 요청할 수 없습니다.");
        }
        if (employee.getBirthDate() == null) {
            // 실측: 생년월일 없이 POST하면 400이었다(MEASUREMENTS.md 6-8, n=2). 외부에 보내기 전에 막는다.
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.BIRTH_DATE_UNKNOWN,
                "생년월일 확인 후 요청할 수 있습니다.");
        }
        if (employee.getFamilyNameKo() == null || employee.getGivenNameKo() == null) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.NAME_NOT_SPLIT,
                "성과 이름을 나눠 저장한 뒤 요청할 수 있습니다(외부 API가 firstName/lastName을 요구합니다).");
        }
        if (!checks.findActiveIds(employee.getId()).isEmpty()) {
            // 친절한 안내를 위한 사전 확인이다. 동시 클릭의 최종 방어는 DB UNIQUE(active_employee_id)다.
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.ALREADY_ACTIVE,
                "이 직원에게 진행 중이거나 확인이 필요한 조회가 있습니다.");
        }
        try {
            return store.insertQueued(employee.getId(), actorAccountId, employee.getFamilyNameKo(),
                employee.getGivenNameKo(), employee.getBirthDate());
        } catch (DataIntegrityViolationException e) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.ALREADY_ACTIVE,
                "이 직원에게 진행 중이거나 확인이 필요한 조회가 있습니다.");
        }
    }

    public Optional<BackgroundCheckViews.Detail> findDetail(long id) {
        return checks.findDetail(id);
    }

    public List<BackgroundCheckViews.Summary> findHistory(Long employeeId) {
        return checks.findSummaries(employeeId);
    }

    public Optional<BackgroundCheckViews.Summary> findLatest(Long employeeId) {
        return checks.findLatestSummary(employeeId);
    }

    /** 화면 자동 갱신용. DB만 읽고 외부 API를 부르지 않는다. */
    public Optional<BackgroundCheckViews.Status> findStatus(long id) {
        return store.findStatus(id);
    }

    /** "다시 조회". 외부 호출은 워커가 다음 tick에 1회만 한다. */
    public void requestRecheck(long id) {
        ensureExists(id);
        if (store.requestRecheck(id) == 0) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.NOT_APPLICABLE,
                "지금 상태에서는 다시 조회할 수 없습니다.");
        }
    }

    /** "생성 안 됨으로 종료". 이 상태가 되어야 같은 직원의 새 요청이 가능해진다. */
    public void closeAsNotCreated(long id) {
        ensureExists(id);
        if (store.closeAsNotCreated(id) == 0) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.NOT_APPLICABLE,
                "확인 필요 상태일 때만 종료할 수 있습니다.");
        }
    }

    private void ensureExists(long id) {
        if (checks.findDetail(id).isEmpty()) {
            throw new BackgroundCheckRequestException(BackgroundCheckRequestException.Reason.CHECK_NOT_FOUND,
                "없는 조회: " + id);
        }
    }
}
