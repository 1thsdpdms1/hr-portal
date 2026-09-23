package com.bitcomputer.hrportal.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Flyway V1이 만든 MySQL 제약이 실제로 동작하는지 확인한다(DESIGN.md 4장).
 * 각 거부 테스트는 "어느 제약 때문에 거부됐는가"까지 MySQL 오류 메시지의 제약 이름으로 확인한다.
 */
class SchemaConstraintTest extends MySqlIntegrationTest {

    @Test
    void CHECK_제약이_실제로_검사되는_MySQL_버전이다() {
        // 8.0.16 미만은 CHECK를 적어도 무시한다(DESIGN.md 2.3).
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        String[] parts = version.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        assertThat(major > 8 || (major == 8 && (minor > 0 || patch >= 16)))
            .as("MySQL %s", version).isTrue();
    }

    @Nested
    class 직원 {

        @Test
        void 동명이인은_사번이_다르면_저장된다() {
            insertEmployee("EMP-900", "김민준", "김", "민준");

            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE name_ko = '김민준'", Long.class);
            assertThat(count).isEqualTo(3);
        }

        @Test
        void 사번은_중복될_수_없고_대소문자만_다른_사번도_중복이다() {
            assertViolates("uq_employee_emp_no",
                "INSERT INTO employee (emp_no, name_ko) VALUES ('EMP-001', '홍길동')");
            assertViolates("uq_employee_emp_no",
                "INSERT INTO employee (emp_no, name_ko) VALUES ('emp-001', '홍길동')");
        }

        @Test
        void 생년월일은_비워_둘_수_있다() {
            jdbc.update("INSERT INTO employee (emp_no, name_ko, family_name_ko, given_name_ko, birth_date) "
                + "VALUES ('EMP-901', '홍길동', '홍', '길동', NULL)");

            assertThat(jdbc.queryForObject(
                "SELECT birth_date IS NULL FROM employee WHERE emp_no = 'EMP-901'", Boolean.class)).isTrue();
        }

        @Test
        void 성과_이름을_합친_값이_원래_성명과_다르면_거부한다() {
            assertViolates("ck_employee_name_split",
                "INSERT INTO employee (emp_no, name_ko, family_name_ko, given_name_ko) "
                    + "VALUES ('EMP-902', '남궁서준', '남궁', '서')");
            // 성과 이름 중 하나만 있는 것도 거부한다.
            assertViolates("ck_employee_name_split",
                "INSERT INTO employee (emp_no, name_ko, family_name_ko, given_name_ko) "
                    + "VALUES ('EMP-903', '남궁서준', '남궁', NULL)");
        }

        @Test
        void CHECK는_합친_값만_검사하고_어디서_나눌지는_정하지_않는다() {
            // "선 / 우진"도 합치면 "선우진"이라 DB는 받아들인다. 어디서 나눌지는 데이터(관리자 입력)로 정한다(DESIGN.md 7.2).
            jdbc.update("INSERT INTO employee (emp_no, name_ko, family_name_ko, given_name_ko) "
                + "VALUES ('EMP-904', '선우진', '선', '우진')");

            assertThat(jdbc.queryForObject(
                "SELECT given_name_ko FROM employee WHERE emp_no = 'EMP-904'", String.class)).isEqualTo("우진");
        }

        @Test
        void 퇴사_상태와_퇴사_시각은_함께_움직인다() {
            assertViolates("ck_employee_terminated_at",
                "INSERT INTO employee (emp_no, name_ko, employment_status) VALUES ('EMP-905', '홍길동', 'TERMINATED')");
            assertViolates("ck_employee_terminated_at",
                "INSERT INTO employee (emp_no, name_ko, employment_status, terminated_at) "
                    + "VALUES ('EMP-906', '홍길동', 'ACTIVE', UTC_TIMESTAMP(6))");
            assertViolates("ck_employee_status",
                "INSERT INTO employee (emp_no, name_ko, employment_status) VALUES ('EMP-907', '홍길동', 'RETIRED')");

            jdbc.update("UPDATE employee SET employment_status = 'TERMINATED', terminated_at = UTC_TIMESTAMP(6) "
                + "WHERE emp_no = 'EMP-010'");
            assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM employee WHERE emp_no = 'EMP-010'", String.class)).isEqualTo("TERMINATED");
        }

        @Test
        void 계정이_연결된_직원은_삭제할_수_없다() {
            long emp = employeeId("EMP-001");
            jdbc.update("INSERT INTO account (login_id, password_hash, role, employee_id) "
                + "VALUES ('emp001-test', '{noop}x', 'EMPLOYEE', ?)", emp);

            assertViolates("fk_account_employee", "DELETE FROM employee WHERE id = ?", emp);
        }
    }

    @Nested
    class 계정 {

        @Test
        void 직원_계정은_반드시_직원과_연결된다() {
            assertViolates("ck_account_employee_link",
                "INSERT INTO account (login_id, password_hash, role) VALUES ('noemp', '{noop}x', 'EMPLOYEE')");
        }

        @Test
        void 관리자_계정은_직원_없이_여러_개_만들_수_있다() {
            insertAdminAccount("admin-a");
            insertAdminAccount("admin-b");

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE role = 'ADMIN' AND employee_id IS NULL AND login_id LIKE 'admin-_'",
                Long.class)).isEqualTo(2);
        }

        @Test
        void 직원_한_명에게_계정은_하나뿐이다() {
            long emp = employeeId("EMP-002");
            jdbc.update("INSERT INTO account (login_id, password_hash, role, employee_id) "
                + "VALUES ('first', '{noop}x', 'EMPLOYEE', ?)", emp);

            assertViolates("uq_account_employee",
                "INSERT INTO account (login_id, password_hash, role, employee_id) "
                    + "VALUES ('second', '{noop}x', 'EMPLOYEE', ?)", emp);
        }

        @Test
        void 로그인_ID는_대소문자만_달라도_중복이다() {
            insertAdminAccount("boss");

            assertViolates("uq_account_login_id",
                "INSERT INTO account (login_id, password_hash, role) VALUES ('BOSS', '{noop}x', 'ADMIN')");
        }

        @Test
        void 역할은_ADMIN과_EMPLOYEE뿐이다() {
            assertViolates("ck_account_role",
                "INSERT INTO account (login_id, password_hash, role) VALUES ('root', '{noop}x', 'SUPER')");
        }
    }

    @Nested
    class Background_Check {

        private long admin;

        private long queue(String empNo) {
            if (admin == 0) {
                admin = insertAdminAccount("bg-admin");
            }
            jdbc.update("INSERT INTO background_check (employee_id, requested_by_id, sent_family_name, "
                + "sent_given_name, sent_birth_date) VALUES (?, ?, '김', '민준', '1990-03-15')", employeeId(empNo), admin);
            return jdbc.queryForObject("SELECT MAX(id) FROM background_check", Long.class);
        }

        private void finish(long id, String checkId) {
            jdbc.update("UPDATE background_check SET sync_state = 'DONE', check_id = ?, result_status = 'clear', "
                + "post_sent_at = UTC_TIMESTAMP(6), next_run_at = NULL WHERE id = ?", checkId, id);
        }

        @Test
        void 새_요청의_기본값은_QUEUED이고_지금_바로_처리_대상이다() {
            long id = queue("EMP-001");

            var row = jdbc.queryForMap("SELECT sync_state, active_employee_id, employee_id, "
                + "ABS(TIMESTAMPDIFF(SECOND, next_run_at, UTC_TIMESTAMP(6))) AS lag_sec, "
                + "pre_list_check_ids IS NULL AS no_pre_list FROM background_check WHERE id = ?", id);

            assertThat(row.get("sync_state")).isEqualTo("QUEUED");
            assertThat(row.get("active_employee_id")).isEqualTo(row.get("employee_id"));
            assertThat(((Number) row.get("lag_sec")).longValue()).isLessThan(5);
            assertThat(((Number) row.get("no_pre_list")).intValue()).isEqualTo(1);
        }

        @Test
        void 같은_직원에게_진행_중인_조회는_한_건뿐이다() {
            queue("EMP-003");

            assertRejectedBy("uq_bgcheck_active_employee", () -> queue("EMP-003"));
        }

        @Test
        void 다른_직원의_조회는_동시에_진행될_수_있다() {
            queue("EMP-003");
            queue("EMP-004");

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_check WHERE active_employee_id IS NOT NULL", Long.class)).isEqualTo(2);
        }

        @Test
        void 끝난_조회는_몇_건이든_이력으로_쌓이고_새_요청을_막지_않는다() {
            finish(queue("EMP-005"), "CHK-test-1");
            finish(queue("EMP-005"), "CHK-test-2");
            queue("EMP-005");

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_check WHERE employee_id = ?", Long.class, employeeId("EMP-005")))
                .isEqualTo(3);
        }

        @Test
        void 확인_필요_상태는_새_요청을_막고_결과_미확인_상태는_막지_않는다() {
            long unknown = queue("EMP-006");
            jdbc.update("UPDATE background_check SET sync_state = 'SUBMIT_UNKNOWN', post_sent_at = UTC_TIMESTAMP(6) "
                + "WHERE id = ?", unknown);
            assertRejectedBy("uq_bgcheck_active_employee", () -> queue("EMP-006"));

            long unresolved = queue("EMP-008");
            jdbc.update("UPDATE background_check SET sync_state = 'UNRESOLVED', check_id = 'CHK-test-3', "
                + "post_sent_at = UTC_TIMESTAMP(6) WHERE id = ?", unresolved);
            queue("EMP-008");
        }

        @Test
        void checkId가_있어야_하는_상태와_없어야_하는_상태가_섞이지_않는다() {
            long id = queue("EMP-009");

            assertViolates("ck_bgcheck_check_id_state",
                "UPDATE background_check SET check_id = 'CHK-test-4' WHERE id = ?", id);
            assertViolates("ck_bgcheck_check_id_state",
                "UPDATE background_check SET sync_state = 'IN_PROGRESS', post_sent_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
        }

        @Test
        void 완료_상태에는_최종_결과가_있어야_한다() {
            long id = queue("EMP-009");

            assertViolates("ck_bgcheck_done_result",
                "UPDATE background_check SET sync_state = 'DONE', check_id = 'CHK-test-5', result_status = 'pending', "
                    + "post_sent_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
        }

        @Test
        void POST_이후_상태에는_보낸_시각이_있어야_한다() {
            long id = queue("EMP-009");

            assertViolates("ck_bgcheck_post_sent",
                "UPDATE background_check SET sync_state = 'SUBMITTING' WHERE id = ?", id);
            assertViolates("ck_bgcheck_post_sent",
                "UPDATE background_check SET sync_state = 'IN_PROGRESS', check_id = 'CHK-test-6' WHERE id = ?", id);
        }

        @Test
        void 허용되지_않은_값은_거부한다() {
            long id = queue("EMP-009");

            // checkId를 함께 넣어 ck_bgcheck_check_id_state는 통과시키고, 상태 값 제약만 어기게 한다.
            assertViolates("ck_bgcheck_sync_state",
                "UPDATE background_check SET sync_state = 'STOPPED', check_id = 'CHK-test-7' WHERE id = ?", id);
            assertViolates("ck_bgcheck_result_status",
                "UPDATE background_check SET result_status = 'unknown' WHERE id = ?", id);
            assertViolates("ck_bgcheck_credit_score",
                "UPDATE background_check SET credit_score = 'bad' WHERE id = ?", id);
            assertViolates("ck_bgcheck_consecutive_failures",
                "UPDATE background_check SET consecutive_failures = -1 WHERE id = ?", id);
        }

        @Test
        void 같은_외부_checkId는_두_행에_저장될_수_없다() {
            finish(queue("EMP-001"), "CHK-dup");

            long other = queue("EMP-002");
            assertViolates("uq_bgcheck_check_id",
                "UPDATE background_check SET sync_state = 'DONE', check_id = 'CHK-dup', result_status = 'clear', "
                    + "post_sent_at = UTC_TIMESTAMP(6) WHERE id = ?", other);
        }

        @Test
        void 요청_전_목록은_SQL_NULL과_빈_배열을_구분해_저장한다() {
            long failed = queue("EMP-001");
            long empty = queue("EMP-002");
            jdbc.update("UPDATE background_check SET pre_list_check_ids = JSON_ARRAY() WHERE id = ?", empty);

            assertThat(jdbc.queryForObject(
                "SELECT pre_list_check_ids IS NULL FROM background_check WHERE id = ?", Boolean.class, failed)).isTrue();
            assertThat(jdbc.queryForObject(
                "SELECT JSON_LENGTH(pre_list_check_ids) FROM background_check WHERE id = ?", Integer.class, empty))
                .isZero();
        }

        @Test
        void 조회_기록이_있는_직원은_삭제할_수_없다() {
            long emp = employeeId("EMP-001");
            queue("EMP-001");

            assertViolates("fk_bgcheck_employee", "DELETE FROM employee WHERE id = ?", emp);
        }
    }

    @Nested
    class 변경_이력 {

        @Test
        void 허용된_필드만_기록할_수_있다() {
            long emp = employeeId("EMP-001");
            long admin = insertAdminAccount("log-admin");

            jdbc.update("INSERT INTO employee_change_log (employee_id, changed_by_id, field_name, old_value, new_value) "
                + "VALUES (?, ?, 'phone', NULL, '010-0000-0000')", emp, admin);
            assertViolates("ck_change_log_field_name",
                "INSERT INTO employee_change_log (employee_id, changed_by_id, field_name) VALUES (?, ?, 'emp_no')",
                emp, admin);
        }
    }
}
