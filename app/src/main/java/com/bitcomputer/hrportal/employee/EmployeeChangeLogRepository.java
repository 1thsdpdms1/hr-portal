package com.bitcomputer.hrportal.employee;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeChangeLogRepository extends JpaRepository<EmployeeChangeLog, Long> {

    List<EmployeeChangeLog> findByEmployeeIdOrderByChangedAtDesc(Long employeeId);

    /**
     * 화면용 조회. 변경자 로그인 ID까지 한 번에 읽는다.
     * 엔티티를 그대로 넘기면 템플릿이 지연 로딩을 건드려 실패한다({@link EmployeeChangeLogView} 설명 참고).
     */
    @Query("select new com.bitcomputer.hrportal.employee.EmployeeChangeLogView("
        + "l.changedAt, l.fieldName, l.oldValue, l.newValue, a.loginId) "
        + "from EmployeeChangeLog l join l.changedBy a "
        + "where l.employee.id = :employeeId order by l.changedAt desc")
    List<EmployeeChangeLogView> findViewsByEmployeeId(@Param("employeeId") Long employeeId);
}
