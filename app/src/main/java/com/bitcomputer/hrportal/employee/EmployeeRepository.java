package com.bitcomputer.hrportal.employee;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmpNo(String empNo);

    List<Employee> findAllByOrderByEmpNoAsc();

    /**
     * 퇴사 처리처럼 "읽고 판단한 뒤 바꾸는" 작업에 쓴다(DESIGN.md 6.3).
     * MySQL의 {@code SELECT ... FOR UPDATE}로 행을 잠가, 관리자 두 명이 동시에 눌러도 한 명만 처리된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.empNo = :empNo")
    Optional<Employee> findByEmpNoForUpdate(@Param("empNo") String empNo);

    /** 새 사번 발급용. 사번 형식이 같은 자릿수라 문자열 최대값이 곧 번호 최대값이다(DESIGN.md 4.1). */
    @Query("select max(e.empNo) from Employee e")
    Optional<String> findMaxEmpNo();
}
