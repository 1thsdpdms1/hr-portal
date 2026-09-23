package com.bitcomputer.hrportal.bgcheck;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 읽기와 INSERT용. 상태 변경(선점·결과 기록) 네이티브 SQL은 6단계에서 별도 Store 클래스에 둔다(DESIGN.md 2.5.3).
 */
public interface BackgroundCheckRepository extends JpaRepository<BackgroundCheck, Long> {

    /** 직원별 조회 이력, 최신순. 인덱스 ix_bgcheck_employee_requested를 쓴다. */
    List<BackgroundCheck> findByEmployeeIdOrderByRequestedAtDesc(Long employeeId);
}
