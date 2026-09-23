package com.bitcomputer.hrportal.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.account.AccountRepository;
import com.bitcomputer.hrportal.account.Role;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheck;
import com.bitcomputer.hrportal.bgcheck.BackgroundCheckRepository;
import com.bitcomputer.hrportal.bgcheck.ResultStatus;
import com.bitcomputer.hrportal.bgcheck.SyncState;
import com.bitcomputer.hrportal.employee.Employee;
import com.bitcomputer.hrportal.employee.EmployeeChangeLog;
import com.bitcomputer.hrportal.employee.EmployeeChangeLogRepository;
import com.bitcomputer.hrportal.employee.EmployeeRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 테이블은 Flyway가 만들고 Hibernate는 검사하지 않으므로(ddl-auto=none, DESIGN.md 2.6),
 * 엔티티 4개가 실제 컬럼과 맞는지 저장·조회로 확인한다. 시각이 UTC로 저장되는지도 여기서 확인한다.
 */
class EntityMappingTest extends MySqlIntegrationTest {

    @Autowired EmployeeRepository employees;
    @Autowired AccountRepository accounts;
    @Autowired BackgroundCheckRepository checks;
    @Autowired EmployeeChangeLogRepository changeLogs;
    @Autowired EntityManager em;

    @Test
    void 계정은_로그인_ID를_소문자로_저장하고_DB_기본값이_채워진다() {
        Employee emp001 = employees.findByEmpNo("EMP-001").orElseThrow();
        Account saved = accounts.saveAndFlush(Account.employee("  Emp001-Test ", "{noop}x", emp001));
        em.clear();

        Account found = accounts.findByLoginId("emp001-test").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(found.isActive()).isTrue();
        assertThat(found.getEmployee().getEmpNo()).isEqualTo("EMP-001");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 시각은_UTC로_저장되고_Java_Instant와_일치한다() {
        // 이 PC는 KST(UTC+9)다. 시간대 설정이 틀리면 아래 차이가 약 9시간(32,400초)으로 나온다.
        Account saved = accounts.saveAndFlush(Account.admin("tz-check", "{noop}x"));
        em.clear();

        Long javaWrittenLag = jdbc.queryForObject(
            "SELECT ABS(TIMESTAMPDIFF(SECOND, updated_at, UTC_TIMESTAMP(6))) FROM account WHERE id = ?",
            Long.class, saved.getId());
        assertThat(javaWrittenLag).as("Java가 쓴 updated_at과 DB의 UTC 현재 시각 차이(초)").isLessThan(5);

        Instant dbWrittenCreatedAt = accounts.findById(saved.getId()).orElseThrow().getCreatedAt();
        assertThat(Duration.between(dbWrittenCreatedAt, Instant.now()).abs())
            .as("DB 기본값 created_at을 Java로 읽은 값과 현재 시각 차이").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void 조회_요청은_INSERT만_하고_상태는_DB_기본값에서_읽는다() {
        Employee emp003 = employees.findByEmpNo("EMP-003").orElseThrow();
        Account admin = accounts.saveAndFlush(Account.admin("bg-admin", "{noop}x"));

        BackgroundCheck saved = checks.saveAndFlush(new BackgroundCheck(
            emp003, admin, emp003.getFamilyNameKo(), emp003.getGivenNameKo(), emp003.getBirthDate()));
        em.clear();

        BackgroundCheck found = checks.findById(saved.getId()).orElseThrow();
        assertThat(found.getSyncState()).isEqualTo(SyncState.QUEUED);
        assertThat(found.getSentFamilyName()).isEqualTo("남궁");
        assertThat(found.getSentGivenName()).isEqualTo("서준");
        assertThat(found.getNextRunAt()).isNotNull();
        assertThat(found.getPreListCheckIds()).as("요청 전 목록 조회 전에는 SQL NULL").isNull();
        assertThat(found.getConsecutiveFailures()).isZero();
        assertThat(checks.findByEmployeeIdOrderByRequestedAtDesc(emp003.getId())).hasSize(1);
    }

    @Test
    void 결과_상태는_API_값_소문자로_읽고_요청_전_목록은_JSON_배열로_읽는다() {
        Employee emp004 = employees.findByEmpNo("EMP-004").orElseThrow();
        Account admin = accounts.saveAndFlush(Account.admin("bg-admin2", "{noop}x"));
        Long id = checks.saveAndFlush(new BackgroundCheck(emp004, admin, "황보", "라온", emp004.getBirthDate())).getId();

        jdbc.update("UPDATE background_check SET sync_state = 'IN_PROGRESS', check_id = 'CHK-map-1', "
            + "result_status = 'pending', post_sent_at = UTC_TIMESTAMP(6), "
            + "pre_list_check_ids = JSON_ARRAY('CHK-old-1', 'CHK-old-2') WHERE id = ?", id);
        em.clear();

        BackgroundCheck found = checks.findById(id).orElseThrow();
        assertThat(found.getSyncState()).isEqualTo(SyncState.IN_PROGRESS);
        assertThat(found.getResultStatus()).isEqualTo(ResultStatus.PENDING);
        assertThat(found.getPreListCheckIds()).containsExactly("CHK-old-1", "CHK-old-2");
        assertThat(found.getPostSentAt()).isNotNull();
    }

    @Test
    void 변경_이력을_저장하고_직원별로_읽는다() {
        Employee emp007 = employees.findByEmpNo("EMP-007").orElseThrow();
        Account admin = accounts.saveAndFlush(Account.admin("log-admin", "{noop}x"));

        changeLogs.saveAndFlush(new EmployeeChangeLog(emp007, admin, "birth_date", null, "1990-01-01"));
        em.clear();

        var logs = changeLogs.findByEmployeeIdOrderByChangedAtDesc(emp007.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getFieldName()).isEqualTo("birth_date");
        assertThat(logs.get(0).getOldValue()).isNull();
        assertThat(logs.get(0).getChangedAt()).isNotNull();
    }
}
