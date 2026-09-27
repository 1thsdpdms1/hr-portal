package com.bitcomputer.hrportal.bgcheck;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background Check 행의 상태를 바꾸는 유일한 창구(DESIGN.md 2.5.3, 2.5.4).
 *
 * <p>규칙 세 가지를 코드로 강제한다.
 * <ol>
 *   <li><b>짧은 트랜잭션</b>: 메서드 하나가 UPDATE 한 문장이다. 외부 API를 기다리는 동안 트랜잭션을 열어 두지 않는다.
 *   <li><b>선점(claim) 조건</b>: 결과를 쓸 때 {@code claim_token}이 내가 가진 값과 같아야 한다.
 *       늦게 돌아온 옛 작업자가 새 결과를 덮어쓰지 못한다.
 *   <li><b>시간은 DB 시계로만</b>: 다음 일정은 항상 {@code UTC_TIMESTAMP(6)}에 초를 더해 계산한다.
 *       서버 시계가 서로 달라도 기준이 하나다(실측: 외부 서버와 우리 PC 시계도 6.5~7.6초 차이났다).
 * </ol>
 *
 * <p>워커(다른 빈)가 이 클래스를 호출하는 구조라서 {@code @Transactional}이 정상 동작한다.
 * 같은 클래스 안에서 자기 메서드를 부르면 트랜잭션이 걸리지 않는다.
 *
 * <p>반환값은 "조건에 맞은 행 수"다. 0이면 다른 작업자가 먼저 가져갔거나 상태가 이미 바뀐 것이므로 결과를 버린다.
 */
@Repository
public class BackgroundCheckStore {

    private final EntityManager em;

    public BackgroundCheckStore(EntityManager em) {
        this.em = em;
    }

    /** 처리할 때가 된 행의 ID. 잠그지 않고 읽는다. 실제 획득은 {@link #claim} 이 한다. */
    @Transactional(readOnly = true)
    public List<Long> findDueIds(int limit) {
        Query query = em.createNativeQuery("""
            SELECT id FROM background_check
             WHERE next_run_at IS NOT NULL AND next_run_at <= UTC_TIMESTAMP(6)
             ORDER BY next_run_at
             LIMIT :limit
            """);
        query.setParameter("limit", limit);
        return query.getResultList().stream().map(value -> ((Number) value).longValue()).toList();
    }

    /**
     * 워커가 한 단계를 처리하는 데 필요한 값을 한 번에 읽는다.
     *
     * <p>엔티티 대신 {@link BackgroundCheckJob}을 쓰는 이유는 그 클래스 설명을 참고한다(지연 로딩 회피).
     * 직원 사번은 조인해서 함께 읽는다.
     */
    @Transactional(readOnly = true)
    public Optional<BackgroundCheckJob> findJob(long id) {
        List<?> rows = em.createNativeQuery("""
                SELECT b.sync_state, b.check_id, b.result_status, e.emp_no,
                       b.sent_family_name, b.sent_given_name, b.sent_birth_date,
                       b.pre_list_check_ids, b.consecutive_failures,
                       TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), b.poll_deadline_at),
                       b.recheck_requested_at IS NOT NULL, b.last_error_code
                  FROM background_check b
                  JOIN employee e ON e.id = b.employee_id
                 WHERE b.id = :id
                """)
            .setParameter("id", id)
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = (Object[]) rows.get(0);
        return Optional.of(new BackgroundCheckJob(
            id,
            SyncState.valueOf((String) r[0]),
            (String) r[1],
            r[2] == null ? null : ResultStatus.fromApiValue((String) r[2]),
            (String) r[3],
            (String) r[4],
            (String) r[5],
            r[6] == null ? null : ((java.sql.Date) r[6]).toLocalDate(),
            (String) r[7],
            r[8] == null ? 0 : ((Number) r[8]).intValue(),
            r[9] == null ? null : ((Number) r[9]).longValue(),
            ((Number) r[10]).intValue() == 1,
            (String) r[11]));
    }

    /**
     * 화면 자동 갱신용 상태. 다음 확인까지 남은 초도 DB가 계산한다.
     * 이 조회는 외부 API를 부르지 않고 상태도 바꾸지 않는다(DESIGN.md 5.2).
     */
    @Transactional(readOnly = true)
    public Optional<BackgroundCheckViews.Status> findStatus(long id) {
        List<?> rows = em.createNativeQuery("""
                SELECT sync_state, result_status,
                       TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_run_at),
                       poll_count, consecutive_failures, last_error_code
                  FROM background_check WHERE id = :id
                """)
            .setParameter("id", id)
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = (Object[]) rows.get(0);
        SyncState syncState = SyncState.valueOf((String) r[0]);
        ResultStatus resultStatus = r[1] == null ? null : ResultStatus.fromApiValue((String) r[1]);
        return Optional.of(new BackgroundCheckViews.Status(
            id,
            syncState.name(),
            syncState.label(),
            resultStatus == null ? null : resultStatus.apiValue(),
            resultStatus == null ? "-" : resultStatus.label(),
            r[2] == null ? null : Math.max(0, ((Number) r[2]).longValue()),
            r[3] == null ? null : ((Number) r[3]).intValue(),
            r[4] == null ? null : ((Number) r[4]).intValue(),
            (String) r[5],
            syncState.isActive()));
    }

    /**
     * 선점. 1이면 이 작업자가 가져간 것이고, 0이면 다른 작업자가 먼저 가져갔거나 상태가 바뀐 것이다.
     * {@code next_run_at}을 "지금 + 선점 시간"으로 밀어 두어, 그동안 다른 작업자가 같은 행을 집지 않는다.
     */
    @Transactional
    public int claim(long id, SyncState expectedState, String token, long leaseSeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET next_run_at = TIMESTAMPADD(SECOND, :lease, UTC_TIMESTAMP(6)),
                       claim_token = :token
                 WHERE id = :id
                   AND sync_state = :state
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= UTC_TIMESTAMP(6)
                """)
            .setParameter("lease", leaseSeconds)
            .setParameter("token", token)
            .setParameter("id", id)
            .setParameter("state", expectedState.name())
            .executeUpdate();
    }

    /** 한 단계에서 외부 호출을 여러 번 할 때, 호출 전마다 선점을 연장한다(DESIGN.md 2.5.4). */
    @Transactional
    public int extendLease(long id, String token, long leaseSeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET next_run_at = TIMESTAMPADD(SECOND, :lease, UTC_TIMESTAMP(6))
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("lease", leaseSeconds)
            .setParameter("token", token)
            .setParameter("id", id)
            .executeUpdate();
    }

    /**
     * POST 직전 커밋. 이 커밋이 "POST를 보냈을 수 있다"는 기록이라, 이후에는 어떤 경로로도 재POST하지 않는다.
     *
     * @param preListJson 요청 전 목록의 checkId 배열(JSON). <b>null이면 SQL NULL</b> = 목록 조회 실패(비교 기준 없음)
     * @param maxWaitSeconds 11장의 자동 확인 마감(180초). {@code poll_deadline_at}은 POST 시점 기준이다
     */
    @Transactional
    public int markSubmitting(long id, String token, String preListJson, long maxWaitSeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'SUBMITTING',
                       post_sent_at = UTC_TIMESTAMP(6),
                       poll_deadline_at = TIMESTAMPADD(SECOND, :maxWait, UTC_TIMESTAMP(6)),
                       pre_list_check_ids = CAST(:preList AS JSON)
                 WHERE id = :id AND claim_token = :token AND sync_state = 'QUEUED'
                """)
            .setParameter("maxWait", maxWaitSeconds)
            .setParameter("preList", preListJson)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /**
     * checkId 연결(POST 201 또는 요청 전후 목록 비교 성공).
     *
     * <p>여기만 {@code claim_token} 조건을 쓰지 않는다(DESIGN.md 2.5.4의 예외). 우리 POST가 직접 받은 checkId는
     * 추측이 아니라 사실이라, 선점이 만료된 뒤 돌아왔더라도 버리면 외부에 만든 기록을 잃는다.
     * 대신 {@code check_id IS NULL}과 상태 조건으로 덮어쓰기를 막는다.
     */
    @Transactional
    public int linkCheckId(long id, String checkId, ResultStatus status, Instant externalCreatedAt,
                           long nextRunDelaySeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'IN_PROGRESS',
                       check_id = :checkId,
                       result_status = :status,
                       external_created_at = :createdAt,
                       next_run_at = TIMESTAMPADD(SECOND, :delay, UTC_TIMESTAMP(6)),
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_error_code = NULL
                 WHERE id = :id
                   AND check_id IS NULL
                   AND sync_state IN ('SUBMITTING', 'SUBMIT_UNKNOWN')
                """)
            .setParameter("checkId", checkId)
            .setParameter("status", status.apiValue())
            .setParameter("createdAt", externalCreatedAt)
            .setParameter("delay", nextRunDelaySeconds)
            .setParameter("id", id)
            .executeUpdate();
    }

    /** POST 400·401: 외부에 확실히 생성되지 않았다. 관리자가 새 요청을 만들 수 있다. */
    @Transactional
    public int markSubmitFailed(long id, String token, String errorCode) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'SUBMIT_FAILED',
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_error_code = :errorCode,
                       last_error_at = UTC_TIMESTAMP(6)
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("errorCode", errorCode)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /**
     * 생성 여부를 알 수 없다(확인 필요). 자동으로 더 하지 않으므로 {@code next_run_at = NULL}이고,
     * 관리자가 "다시 조회"를 눌러야 워커가 요청 후 목록을 1회 더 본다.
     */
    @Transactional
    public int markSubmitUnknown(long id, String token, String errorCode, Integer diffCount) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'SUBMIT_UNKNOWN',
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_diff_count = :diffCount,
                       last_error_code = :errorCode,
                       last_error_at = UTC_TIMESTAMP(6)
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("diffCount", diffCount)
            .setParameter("errorCode", errorCode)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /** 상세가 포함된 최종 응답을 받았다 → 완료. 결과 컬럼은 이렇게 2xx 응답에서만 바뀐다. */
    @Transactional
    public int applyFinalDetail(long id, String token, ResultStatus status, Boolean criminalRecord,
                                Boolean educationVerified, Boolean employmentVerified, String creditScore,
                                Instant externalCreatedAt, Instant completedAt) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'DONE',
                       result_status = :status,
                       criminal_record = :criminalRecord,
                       education_verified = :educationVerified,
                       employment_verified = :employmentVerified,
                       credit_score = :creditScore,
                       external_created_at = COALESCE(:createdAt, external_created_at),
                       completed_at = :completedAt,
                       last_polled_at = UTC_TIMESTAMP(6),
                       poll_count = poll_count + 1,
                       consecutive_failures = 0,
                       last_error_code = NULL,
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("status", status.apiValue())
            .setParameter("criminalRecord", criminalRecord)
            .setParameter("educationVerified", educationVerified)
            .setParameter("employmentVerified", employmentVerified)
            .setParameter("creditScore", creditScore)
            .setParameter("createdAt", externalCreatedAt)
            .setParameter("completedAt", completedAt)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /** 아직 pending. 8장대로 실패로 세지 않고 연속 실패 수를 0으로 되돌린다. */
    @Transactional
    public int applyPending(long id, String token, long nextRunDelaySeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET result_status = 'pending',
                       last_polled_at = UTC_TIMESTAMP(6),
                       poll_count = poll_count + 1,
                       consecutive_failures = 0,
                       last_error_code = NULL,
                       next_run_at = TIMESTAMPADD(SECOND, :delay, UTC_TIMESTAMP(6)),
                       claim_token = NULL,
                       recheck_requested_at = NULL
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("delay", nextRunDelaySeconds)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /**
     * 일시적 실패(500·503·타임아웃·네트워크). <b>결과 컬럼은 건드리지 않는다</b> —
     * 실측에서 순차 GET의 59.1%가 이런 실패였다(n=413). 실패가 결과를 덮어쓰면 "조회 실패"가 "이상 있음"처럼 보인다.
     */
    @Transactional
    public int recordTransientFailure(long id, String token, String errorCode, long nextRunDelaySeconds) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET consecutive_failures = consecutive_failures + 1,
                       last_polled_at = UTC_TIMESTAMP(6),
                       poll_count = poll_count + 1,
                       last_error_code = :errorCode,
                       last_error_at = UTC_TIMESTAMP(6),
                       next_run_at = TIMESTAMPADD(SECOND, :delay, UTC_TIMESTAMP(6)),
                       claim_token = NULL,
                       recheck_requested_at = NULL
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("errorCode", errorCode)
            .setParameter("delay", nextRunDelaySeconds)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /** 자동 확인 중단(결과 미확인). checkId는 지우지 않는다. 500·503을 flagged로 바꾸지 않는다. */
    @Transactional
    public int markUnresolved(long id, String token, String errorCode) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'UNRESOLVED',
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_error_code = :errorCode,
                       last_error_at = UTC_TIMESTAMP(6)
                 WHERE id = :id AND claim_token = :token
                """)
            .setParameter("errorCode", errorCode)
            .setParameter("id", id)
            .setParameter("token", token)
            .executeUpdate();
    }

    /**
     * 180초 마감이 지난 행을 정리한다(11장). 선점 중인 행({@code claim_token}이 있는 행)은 건드리지 않는다 —
     * 지금 외부 호출이 진행 중일 수 있기 때문이다.
     */
    @Transactional
    public int expireDeadlines() {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'UNRESOLVED',
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_error_code = 'DEADLINE',
                       last_error_at = UTC_TIMESTAMP(6)
                 WHERE sync_state = 'IN_PROGRESS'
                   AND claim_token IS NULL
                   AND poll_deadline_at IS NOT NULL
                   AND poll_deadline_at <= UTC_TIMESTAMP(6)
                """)
            .executeUpdate();
    }

    /**
     * 관리자의 "다시 조회". 컨트롤러가 호출하며 외부 호출은 하지 않는다. 워커가 다음 tick에 1회만 처리한다.
     * 이미 요청돼 있으면 0행이라 중복으로 쌓이지 않는다.
     */
    @Transactional
    public int requestRecheck(long id) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET recheck_requested_at = UTC_TIMESTAMP(6),
                       next_run_at = UTC_TIMESTAMP(6)
                 WHERE id = :id
                   AND recheck_requested_at IS NULL
                   AND (sync_state = 'UNRESOLVED'
                        OR (sync_state = 'SUBMIT_UNKNOWN' AND pre_list_check_ids IS NOT NULL))
                """)
            .setParameter("id", id)
            .executeUpdate();
    }

    /** 관리자의 "생성 안 됨으로 종료". 이 상태가 되어야 같은 직원의 새 요청이 가능하다(UNIQUE active_employee_id). */
    @Transactional
    public int closeAsNotCreated(long id) {
        return em.createNativeQuery("""
                UPDATE background_check
                   SET sync_state = 'SUBMIT_FAILED',
                       next_run_at = NULL,
                       claim_token = NULL,
                       recheck_requested_at = NULL,
                       last_error_code = 'UNKNOWN',
                       last_error_at = UTC_TIMESTAMP(6)
                 WHERE id = :id AND sync_state = 'SUBMIT_UNKNOWN'
                """)
            .setParameter("id", id)
            .executeUpdate();
    }

    /** 테스트·화면 표시용: 같은 checkId가 이미 다른 행에 연결돼 있는지(요청 전후 비교에서 후보를 거를 때 쓴다). */
    @Transactional(readOnly = true)
    public List<String> findLinkedCheckIds(List<String> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return em.createNativeQuery("SELECT check_id FROM background_check WHERE check_id IN (:ids)")
            .setParameter("ids", candidates)
            .getResultList();
    }

    /** 새 조회 요청(QUEUED) INSERT. 진행 중 1건 제한은 DB의 UNIQUE(active_employee_id)가 최종 보장한다. */
    @Transactional
    public long insertQueued(long employeeId, long requestedByAccountId, String familyName, String givenName,
                             LocalDate birthDate) {
        em.createNativeQuery("""
                INSERT INTO background_check
                    (employee_id, requested_by_id, sent_family_name, sent_given_name, sent_birth_date)
                VALUES (:employeeId, :accountId, :familyName, :givenName, :birthDate)
                """)
            .setParameter("employeeId", employeeId)
            .setParameter("accountId", requestedByAccountId)
            .setParameter("familyName", familyName)
            .setParameter("givenName", givenName)
            .setParameter("birthDate", birthDate)
            .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }
}
