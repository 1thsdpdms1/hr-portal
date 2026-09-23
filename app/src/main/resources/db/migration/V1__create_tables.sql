-- V1: 테이블, 제약조건, 인덱스 (DESIGN.md 4장)
-- 규칙
--   * 시각은 모두 DATETIME(6), UTC로 저장한다. DB 현재 시각은 UTC_TIMESTAMP(6)만 쓴다(NOW()는 세션 시간대를 따른다).
--   * 상태 값은 ENUM 대신 VARCHAR + CHECK. CHECK는 MySQL 8.0.16 이상에서만 실제로 검사된다.
--   * 외래키에 ON DELETE/ON UPDATE 절을 쓰지 않는다. InnoDB 기본 동작(RESTRICT)으로 두면
--     "CHECK·생성 칼럼에 쓰인 컬럼의 외래키에는 CASCADE/SET NULL 금지" 제한에 걸리지 않고,
--     직원 행을 지우려 하면 DB가 막는다(퇴사는 삭제가 아니다).
--   * CHECK 제약 이름은 MySQL에서 스키마 전체에서 겹치면 안 되므로 테이블 이름을 앞에 붙인다.
--   * 한글 이름 컬럼만 utf8mb4_bin: 기본 정렬(utf8mb4_0900_ai_ci)은 대소문자·악센트를 무시하고 비교하므로,
--     "성 + 이름 = 원문" 검사를 글자 단위로 정확히 하려고 바이너리 비교를 쓴다.

CREATE TABLE employee (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    emp_no            VARCHAR(20)  NOT NULL,
    name_ko           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    family_name_ko    VARCHAR(10)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    given_name_ko     VARCHAR(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    birth_date        DATE         NULL,          -- NULL = 미확인 (EMP-007). 가짜 날짜를 넣지 않는다.
    phone             VARCHAR(20)  NULL,
    email             VARCHAR(254) NULL,
    employment_status VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    terminated_at     DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at        DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    -- 사번이 사람을 구분하는 유일한 업무 키. 성명에는 UNIQUE를 걸지 않는다(김민준 2명).
    CONSTRAINT uq_employee_emp_no UNIQUE (emp_no),
    -- CHECK ①: 성과 이름은 둘 다 비었거나, 둘 다 있고 합치면 원문과 같다. (MySQL의 || 는 OR이므로 CONCAT)
    CONSTRAINT ck_employee_name_split CHECK (
        (family_name_ko IS NULL AND given_name_ko IS NULL)
        OR (family_name_ko IS NOT NULL AND given_name_ko IS NOT NULL
            AND CONCAT(family_name_ko, given_name_ko) = name_ko)),
    CONSTRAINT ck_employee_status CHECK (employment_status IN ('ACTIVE', 'TERMINATED')),
    -- CHECK ②: 퇴사 상태와 퇴사 시각은 함께 움직인다. 허용 값 검사는 ck_employee_status가 따로 하므로,
    -- 여기서는 두 상태의 규칙만 적는다(잘못된 상태 값이 두 제약을 동시에 어기지 않게 해 원인을 한 곳에서 알 수 있다).
    CONSTRAINT ck_employee_terminated_at CHECK (
        (employment_status <> 'ACTIVE' OR terminated_at IS NULL)
        AND (employment_status <> 'TERMINATED' OR terminated_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE account (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    login_id       VARCHAR(50)  NOT NULL,         -- 소문자로 정규화해 저장. 기본 정렬이 대소문자를 무시하므로 Admin/admin도 중복.
    password_hash  VARCHAR(100) NOT NULL,         -- {bcrypt} + BCrypt 해시. 평문 저장 금지.
    role           VARCHAR(10)  NOT NULL DEFAULT 'EMPLOYEE',
    employee_id    BIGINT       NULL,             -- 관리자 계정은 직원과 연결하지 않는다.
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    deactivated_at DATETIME(6)  NULL,
    last_login_at  DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at     DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    CONSTRAINT uq_account_login_id UNIQUE (login_id),
    -- 직원 한 명에게 계정은 최대 1개. UNIQUE는 NULL을 여러 개 허용하므로 관리자 계정은 여러 개 가능.
    CONSTRAINT uq_account_employee UNIQUE (employee_id),
    CONSTRAINT fk_account_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT ck_account_role CHECK (role IN ('ADMIN', 'EMPLOYEE')),
    -- CHECK ③: 일반 직원 계정은 반드시 직원 레코드와 연결된다.
    CONSTRAINT ck_account_employee_link CHECK (role <> 'EMPLOYEE' OR employee_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE background_check (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    employee_id          BIGINT      NOT NULL,
    requested_by_id      BIGINT      NOT NULL,
    check_id             VARCHAR(64) NULL,        -- 외부 checkId. POST 결과가 불확실하면 없을 수 있다.
    sync_state           VARCHAR(16) NOT NULL DEFAULT 'QUEUED',   -- 연동 상태
    -- 진행 중 1건 제한용 생성 칼럼. MySQL에는 조건부 UNIQUE 인덱스가 없으므로
    -- "진행 중이면 직원 ID, 끝났으면 NULL"을 DB가 계산하게 하고 여기에 UNIQUE를 건다(DESIGN.md 4.3).
    active_employee_id   BIGINT GENERATED ALWAYS AS (
        CASE WHEN sync_state IN ('QUEUED', 'SUBMITTING', 'SUBMIT_UNKNOWN', 'IN_PROGRESS')
             THEN employee_id END) STORED,
    result_status        VARCHAR(10) NULL,        -- 업무 결과. 2xx 응답 본문에서만 갱신한다.
    sent_family_name     VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sent_given_name      VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sent_birth_date      DATE        NOT NULL,
    criminal_record      BOOLEAN     NULL,        -- 민감 상세
    education_verified   BOOLEAN     NULL,        -- 민감 상세
    employment_verified  BOOLEAN     NULL,        -- 민감 상세
    credit_score         VARCHAR(10) NULL,        -- 민감 상세
    detail_purged_at     DATETIME(6) NULL,
    requested_at         DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    external_created_at  DATETIME(6) NULL,        -- 외부 서버 시각
    completed_at         DATETIME(6) NULL,        -- 외부 서버 시각
    last_polled_at       DATETIME(6) NULL,
    poll_count           INT         NOT NULL DEFAULT 0,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    last_error_code      VARCHAR(20) NULL,        -- 연동 실패 종류. 업무 결과가 아니다.
    last_error_at        DATETIME(6) NULL,
    next_run_at          DATETIME(6) NULL DEFAULT (UTC_TIMESTAMP(6)),  -- 다음 처리 가능 시각(선점에도 사용)
    claim_token          VARCHAR(36) NULL,        -- 지금 단계를 맡은 작업자
    post_sent_at         DATETIME(6) NULL,        -- POST 직전 커밋 시각. 있으면 절대 재POST하지 않는다.
    pre_list_check_ids   JSON        NULL,        -- 요청 전 목록. SQL NULL = 조회 실패, [] = 성공·기록 없음
    last_diff_count      INT         NULL,
    poll_deadline_at     DATETIME(6) NULL,        -- post_sent_at + 180초 (MEASUREMENTS.md 11장)
    recheck_requested_at DATETIME(6) NULL,
    updated_at           DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    CONSTRAINT uq_bgcheck_check_id UNIQUE (check_id),
    CONSTRAINT uq_bgcheck_active_employee UNIQUE (active_employee_id),
    KEY ix_bgcheck_due (sync_state, next_run_at),                    -- 워커가 "처리할 때가 된 행"을 찾는 조건
    KEY ix_bgcheck_employee_requested (employee_id, requested_at),   -- 직원별 조회 이력(외래키 인덱스 겸용)
    CONSTRAINT fk_bgcheck_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_bgcheck_requested_by FOREIGN KEY (requested_by_id) REFERENCES account (id),
    -- CHECK ④: 연동 상태 7개
    CONSTRAINT ck_bgcheck_sync_state CHECK (sync_state IN
        ('QUEUED', 'SUBMITTING', 'SUBMIT_FAILED', 'SUBMIT_UNKNOWN', 'IN_PROGRESS', 'DONE', 'UNRESOLVED')),
    CONSTRAINT ck_bgcheck_result_status CHECK (result_status IS NULL OR result_status IN ('pending', 'clear', 'flagged')),
    CONSTRAINT ck_bgcheck_credit_score CHECK (credit_score IS NULL OR credit_score IN ('excellent', 'good', 'fair', 'poor')),
    -- CHECK ⑤: checkId가 없는 상태와 있는 상태가 섞이지 않는다.
    CONSTRAINT ck_bgcheck_check_id_state CHECK (
        (sync_state IN ('QUEUED', 'SUBMITTING', 'SUBMIT_FAILED', 'SUBMIT_UNKNOWN')) = (check_id IS NULL)),
    -- CHECK ⑥: 완료 상태에는 최종 결과가 있다.
    CONSTRAINT ck_bgcheck_done_result CHECK (sync_state <> 'DONE' OR result_status IN ('clear', 'flagged')),
    -- CHECK ⑦: POST 이후 상태에는 "보낸 시각"이 있다. (SUBMIT_FAILED는 "생성 안 됨으로 종료"에서도 오므로 제외)
    CONSTRAINT ck_bgcheck_post_sent CHECK (
        sync_state NOT IN ('SUBMITTING', 'SUBMIT_UNKNOWN', 'IN_PROGRESS', 'DONE', 'UNRESOLVED')
        OR post_sent_at IS NOT NULL),
    CONSTRAINT ck_bgcheck_poll_count CHECK (poll_count >= 0),
    CONSTRAINT ck_bgcheck_consecutive_failures CHECK (consecutive_failures >= 0),
    CONSTRAINT ck_bgcheck_last_diff_count CHECK (last_diff_count IS NULL OR last_diff_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE employee_change_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    employee_id   BIGINT      NOT NULL,
    changed_by_id BIGINT      NOT NULL,
    field_name    VARCHAR(30) NOT NULL,
    old_value     TEXT        NULL,
    new_value     TEXT        NULL,
    changed_at    DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    KEY ix_change_log_employee_changed (employee_id, changed_at),
    CONSTRAINT fk_change_log_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_change_log_changed_by FOREIGN KEY (changed_by_id) REFERENCES account (id),
    CONSTRAINT ck_change_log_field_name CHECK (field_name IN
        ('phone', 'email', 'birth_date', 'name_ko', 'family_name_ko', 'given_name_ko', 'employment_status'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
