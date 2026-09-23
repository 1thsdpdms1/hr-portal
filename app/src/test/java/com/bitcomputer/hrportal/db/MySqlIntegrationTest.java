package com.bitcomputer.hrportal.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 MySQL(hr_portal_test)에 붙는 통합 테스트의 공통 부모.
 *
 * <p>H2 같은 가짜 DB를 쓰지 않는 이유: 생성 칼럼, CHECK 동작, UTC_TIMESTAMP 같은 MySQL 전용 동작을
 * 재현하지 못해 테스트가 통과해도 믿을 수 없다(DESIGN.md 2.2). Docker가 없어 Testcontainers도 쓰지 않는다.
 *
 * <p>{@code @Transactional}: 각 테스트가 끝나면 롤백되므로 테스트가 넣은 행은 남지 않고 시드 10명도 그대로다.
 * InnoDB는 제약 위반이 난 문장만 취소하고 트랜잭션은 계속 쓸 수 있어, 한 테스트 안에서 여러 위반을 차례로 확인할 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class MySqlIntegrationTest {

    protected static final String TEST_DATABASE = "hr_portal_test";

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void 테스트_DB에만_연결되어_있는지_확인한다() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database)
            .as("통합 테스트는 %s에서만 실행한다. TEST_DB_URL을 확인하라.", TEST_DATABASE)
            .isEqualTo(TEST_DATABASE);
    }

    /** SQL이 지정한 제약(CHECK, UNIQUE, FK) 때문에 거부되는지 확인한다. MySQL 오류 메시지에 제약 이름이 들어 있다. */
    protected void assertViolates(String constraintName, String sql, Object... args) {
        assertRejectedBy(constraintName, () -> jdbc.update(sql, args));
    }

    protected void assertRejectedBy(String constraintName, ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOf(DataAccessException.class)
            .rootCause()
            .hasMessageContaining(constraintName);
    }

    protected long employeeId(String empNo) {
        return jdbc.queryForObject("SELECT id FROM employee WHERE emp_no = ?", Long.class, empNo);
    }

    protected long insertEmployee(String empNo, String nameKo, String family, String given) {
        jdbc.update("INSERT INTO employee (emp_no, name_ko, family_name_ko, given_name_ko, birth_date) "
            + "VALUES (?, ?, ?, ?, '1990-01-01')", empNo, nameKo, family, given);
        return employeeId(empNo);
    }

    protected long insertAdminAccount(String loginId) {
        jdbc.update("INSERT INTO account (login_id, password_hash, role) VALUES (?, '{noop}test', 'ADMIN')", loginId);
        return jdbc.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }
}
