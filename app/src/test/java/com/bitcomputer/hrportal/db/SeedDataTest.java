package com.bitcomputer.hrportal.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import com.bitcomputer.hrportal.employee.EmploymentStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 과제 설명서(InterviewTasks.txt 3장)의 직원 10명이 글자 그대로 들어갔는지 확인한다(DESIGN.md 7.1).
 * 아래 EXPECTED는 과제 표를 그대로 옮긴 값이다. 마이그레이션 SQL을 보고 만든 값이 아니다.
 */
class SeedDataTest extends MySqlIntegrationTest {

    record Seed(String empNo, String nameKo, LocalDate birthDate) {}

    static final List<Seed> EXPECTED = List.of(
        new Seed("EMP-001", "김민준", LocalDate.of(1990, 3, 15)),
        new Seed("EMP-002", "김민준", LocalDate.of(1994, 11, 2)),
        new Seed("EMP-003", "남궁서준", LocalDate.of(1988, 7, 21)),
        new Seed("EMP-004", "황보라온", LocalDate.of(1995, 2, 9)),
        new Seed("EMP-005", "김솔", LocalDate.of(1992, 12, 30)),
        new Seed("EMP-006", "선우진", LocalDate.of(1991, 5, 5)),
        new Seed("EMP-007", "이서연", null),                       // 확인되지 않음
        new Seed("EMP-008", "박민준", LocalDate.of(1993, 8, 17)),
        new Seed("EMP-009", "최지우", LocalDate.of(1996, 4, 3)),
        new Seed("EMP-010", "정하윤", LocalDate.of(1989, 10, 11)));

    @Autowired
    EmployeeRepository employees;

    @Test
    void 시드_직원은_정확히_과제의_10명이다() {
        List<Seed> actual = jdbc.query(
            "SELECT emp_no, name_ko, birth_date FROM employee ORDER BY emp_no",
            (rs, i) -> new Seed(rs.getString("emp_no"), rs.getString("name_ko"),
                rs.getObject("birth_date", LocalDate.class)));

        assertThat(actual).containsExactlyElementsOf(EXPECTED);
    }

    @Test
    void JPA로_읽어도_한글_이름과_미확인_생년월일이_그대로다() {
        List<Employee> all = employees.findAllByOrderByEmpNoAsc();

        assertThat(all).extracting(Employee::getEmpNo, Employee::getNameKo, Employee::getBirthDate)
            .containsExactlyElementsOf(EXPECTED.stream()
                .map(s -> org.assertj.core.groups.Tuple.tuple(s.empNo(), s.nameKo(), s.birthDate()))
                .toList());
        assertThat(all).allSatisfy(e -> {
            assertThat(e.getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
            assertThat(e.getTerminatedAt()).isNull();
        });

        Employee emp007 = employees.findByEmpNo("EMP-007").orElseThrow();
        assertThat(emp007.isBirthDateUnknown()).isTrue();
    }

    @Test
    void 동명이인_김민준_두_명은_사번으로만_구분된다() {
        List<String> empNos = jdbc.queryForList(
            "SELECT emp_no FROM employee WHERE name_ko = '김민준' ORDER BY emp_no", String.class);

        assertThat(empNos).containsExactly("EMP-001", "EMP-002");
    }

    @Test
    void 성과_이름을_합치면_원래_성명이고_복성은_두_글자_성으로_나뉜다() {
        Map<String, String[]> split = Map.of(
            "EMP-003", new String[] {"남궁", "서준"},
            "EMP-004", new String[] {"황보", "라온"},
            "EMP-006", new String[] {"선우", "진"},   // "선 / 우진"도 가능: 회사 확인 전 기본안(DESIGN.md 7.2)
            "EMP-005", new String[] {"김", "솔"});

        split.forEach((empNo, expected) -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT family_name_ko, given_name_ko FROM employee WHERE emp_no = ?", empNo);
            assertThat(row.get("family_name_ko")).as(empNo).isEqualTo(expected[0]);
            assertThat(row.get("given_name_ko")).as(empNo).isEqualTo(expected[1]);
        });

        Long mismatches = jdbc.queryForObject(
            "SELECT COUNT(*) FROM employee WHERE CONCAT(family_name_ko, given_name_ko) <> name_ko", Long.class);
        assertThat(mismatches).isZero();
    }

    @Test
    void Flyway가_V1과_V2를_성공적으로_적용했다() {
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history).extracting(r -> r.get("version")).containsExactly("1", "2");
        assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isIn(true, 1, (byte) 1));
    }
}
