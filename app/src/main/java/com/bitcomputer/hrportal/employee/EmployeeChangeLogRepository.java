package com.bitcomputer.hrportal.employee;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeChangeLogRepository extends JpaRepository<EmployeeChangeLog, Long> {

    List<EmployeeChangeLog> findByEmployeeIdOrderByChangedAtDesc(Long employeeId);
}
