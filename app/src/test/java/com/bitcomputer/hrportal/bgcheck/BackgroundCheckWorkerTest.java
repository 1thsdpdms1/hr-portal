package com.bitcomputer.hrportal.bgcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커 단계별 동작(DESIGN.md 2.5, 4.3, 6.4 / 9장 6번 행의 시나리오 목록).
 *
 * <p>외부 API는 {@link FakeBackgroundCheckApi}로 바꾸고, 스케줄러는 꺼 둔 채 {@code tick()}을 직접 부른다.
 * 시간은 기다리지 않는다. "때가 됐다"는 SQL로 {@code next_run_at}을 과거로 옮겨 만든다.
 *
 * <p>트랜잭션을 끄고({@code NOT_SUPPORTED}) 실제로 커밋한다. 워커는 단계마다 짧은 트랜잭션을 쓰고 그 사이에
 * 커밋된 값을 다시 읽으므로, 테스트 트랜잭션 안에 가두면 실제와 다른 흐름이 된다.
 * 넣은 행은 {@link #정리한다()}에서 지운다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundCheckWorkerTest extends MySqlIntegrationTest {

    private static final String EMP_NO = "EMP-003";

    @Autowired BackgroundCheckStore store;
    @Autowired BgCheckProperties properties;
    @Autowired ObjectMapper objectMapper;

    private FakeBackgroundCheckApi api;
    private BackgroundCheckWorker worker;
    private long employeeId;
    private long adminAccountId;

    @BeforeEach
    void 준비한다() {
        api = new FakeBackgroundCheckApi();
        worker = new BackgroundCheckWorker(store, api, properties, objectMapper);
        employeeId = employeeId(EMP_NO);
        adminAccountId = insertAdminAccount("bgcheck-worker-admin");
    }

    @AfterEach
    void 정리한다() {
        jdbc.update("DELETE FROM background_check WHERE requested_by_id = ?", adminAccountId);
        jdbc.update("DELETE FROM account WHERE id = ?", adminAccountId);
    }

    private long 요청을_넣는다() {
        return store.insertQueued(employeeId, adminAccountId, "남궁", "서준", LocalDate.of(1988, 7, 21));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM background_check WHERE id = ?", id);
    }

    /** next_run_at을 과거로 옮겨 "처리할 때가 됐다"를 만든다. */
    private void 지금_처리할_때로_만든다(long id) {
        jdbc.update("UPDATE background_check SET next_run_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) "
            + "WHERE id = ?", id);
    }

    /** next_run_at이 지금부터 몇 초 뒤로 예약됐는지. */
    private long 예약까지_남은초(long id) {
        return jdbc.queryForObject(
            "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_run_at) FROM background_check WHERE id = ?",
            Long.class, id);
    }

    @Test
    void POST가_pending이면_10장대로_35초_뒤_첫_폴링을_예약한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list("CHK-old"))
            .createReturns(FakeBackgroundCheckApi.created("CHK-1", ResultStatus.PENDING));

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("check_id")).isEqualTo("CHK-1");
        assertThat(row.get("result_status")).isEqualTo("pending");
        assertThat(row.get("post_sent_at")).isNotNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(예약까지_남은초(id)).isBetween(30L, 35L);
        // 마감은 POST 시점 + 180초(11장)
        long 마감까지 = jdbc.queryForObject(
            "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), poll_deadline_at) FROM background_check WHERE id = ?",
            Long.class, id);
        assertThat(마감까지).isBetween(170L, 180L);
        assertThat(api.createCalls()).hasSize(1);
    }

    @Test
    void POST가_즉시_clear면_폴링_없이_상세_GET을_바로_보낸다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-2", ResultStatus.CLEAR))
            .getReturns(FakeBackgroundCheckApi.detail("CHK-2", ResultStatus.CLEAR));

        worker.tick();                       // 제출 단계 → 상세 GET을 즉시(0초 뒤)로 예약
        assertThat(예약까지_남은초(id)).isLessThanOrEqualTo(0);
        worker.tick();                       // 상세 GET

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("DONE");
        assertThat(row.get("result_status")).isEqualTo("clear");
        assertThat(row.get("credit_score")).isEqualTo("good");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("next_run_at")).isNull();
        assertThat(api.getCalls()).containsExactly("CHK-2");
    }

    @Test
    void processingMs가_있으면_POST_시점_더하기_처리시간_더하기_여유로_다음_조회를_예약한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-P1", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-P1", 55_313L));   // 실측 p50

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        // post_sent_at ≈ 방금 + 55.313초 + 여유 3초 → 지금부터 약 58초 뒤
        assertThat(예약까지_남은초(id)).isBetween(53L, 58L);
        assertThat(((Number) row(id).get("consecutive_failures")).intValue()).isZero();
    }

    @Test
    void processingMs로_잡은_시각이_이미_지났으면_20초_간격으로_돌아간다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-P2", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-P2", 55_313L));
        worker.tick();
        // POST가 100초 전이었던 상황: 목표(post_sent_at + 58초)는 이미 지났는데 아직 pending → 값이 틀렸다
        jdbc.update("UPDATE background_check SET post_sent_at = TIMESTAMPADD(SECOND, -100, UTC_TIMESTAMP(6)) "
            + "WHERE id = ?", id);
        지금_처리할_때로_만든다(id);

        worker.tick();

        assertThat(예약까지_남은초(id)).isBetween(15L, 20L);
    }

    @Test
    void processingMs로_잡은_시각이_마감_너머면_20초_간격으로_돌아간다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-P3", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-P3", 500_000L));   // 180초 마감 너머

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        assertThat(예약까지_남은초(id)).isBetween(15L, 20L);
    }

    @Test
    void processingMs_사용을_끄면_20초_간격만_쓴다() {
        BgCheckProperties off = new BgCheckProperties(properties.baseUrl(), properties.candidateKey(),
            properties.getTimeout(), properties.postTimeout(), properties.firstPollDelay(),
            properties.pollInterval(), false, properties.processingMsMargin(), properties.maxWait(),
            properties.maxConsecutiveFailures(), properties.tickDelay(), properties.leaseMargin(),
            properties.batchSize());
        BackgroundCheckWorker offWorker = new BackgroundCheckWorker(store, api, off, objectMapper);
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-P4", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-P4", 55_313L));

        offWorker.tick();
        지금_처리할_때로_만든다(id);
        offWorker.tick();

        assertThat(예약까지_남은초(id)).isBetween(15L, 20L);
    }

    @Test
    void processingMs가_없는_pending이면_20초_뒤로_다시_예약하고_실패로_세지_않는다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-3", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-3"));

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("result_status")).isEqualTo("pending");
        assertThat(((Number) row.get("consecutive_failures")).intValue()).isZero();
        assertThat(((Number) row.get("poll_count")).intValue()).isEqualTo(1);
        assertThat(예약까지_남은초(id)).isBetween(15L, 20L);
    }

    @Test
    void 폴링_503은_결과를_바꾸지_않고_retryAfter만큼_뒤로_미룬다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-4", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.httpFailure(503, Duration.ofSeconds(30)));

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("result_status")).isEqualTo("pending");     // 실패가 결과를 덮어쓰지 않는다
        assertThat(row.get("sync_state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("last_error_code")).isEqualTo("HTTP_503");
        assertThat(((Number) row.get("consecutive_failures")).intValue()).isEqualTo(1);
        assertThat(예약까지_남은초(id)).isBetween(25L, 30L);              // Retry-After를 따른다
    }

    @Test
    void retryAfter가_없는_실패는_20초_뒤에_다시_시도한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-5", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.httpFailure(500, null));

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        assertThat(row(id).get("last_error_code")).isEqualTo("HTTP_500");
        assertThat(예약까지_남은초(id)).isBetween(15L, 20L);
    }

    @Test
    void 일시적_실패가_연속_8회면_결과_미확인으로_중단한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-6", ResultStatus.PENDING));
        for (int i = 0; i < properties.maxConsecutiveFailures(); i++) {
            api.getReturns(FakeBackgroundCheckApi.httpFailure(500, null));
        }

        worker.tick();
        for (int i = 0; i < properties.maxConsecutiveFailures(); i++) {
            지금_처리할_때로_만든다(id);
            worker.tick();
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("UNRESOLVED");
        assertThat(row.get("check_id")).isEqualTo("CHK-6");            // checkId는 보존한다
        assertThat(row.get("result_status")).isEqualTo("pending");     // flagged로 바꾸지 않는다
        assertThat(row.get("next_run_at")).isNull();
        // 8번째 시도에서 중단하므로 GET은 8번 나갔다
        assertThat(api.getCalls()).hasSize(properties.maxConsecutiveFailures());
    }

    @Test
    void 마감_180초가_지나면_결과_미확인으로_정리한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-7", ResultStatus.PENDING));
        worker.tick();

        jdbc.update("UPDATE background_check SET poll_deadline_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) "
            + "WHERE id = ?", id);
        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("UNRESOLVED");
        assertThat(row.get("last_error_code")).isEqualTo("DEADLINE");
        assertThat(row.get("check_id")).isEqualTo("CHK-7");
    }

    @Test
    void GET_404는_반복하지_않고_결과_미확인으로_둔다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-8", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.httpFailure(404, null));

        worker.tick();
        지금_처리할_때로_만든다(id);
        worker.tick();

        assertThat(row(id).get("sync_state")).isEqualTo("UNRESOLVED");
        assertThat(row(id).get("last_error_code")).isEqualTo("HTTP_404");
    }

    @Test
    void POST_400은_요청_실패로_끝나고_같은_직원의_새_요청을_막지_않는다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.httpFailure(400, null));

        worker.tick();

        assertThat(row(id).get("sync_state")).isEqualTo("SUBMIT_FAILED");
        assertThat(row(id).get("check_id")).isNull();
        // 진행 중이 아니므로 같은 직원으로 다시 요청할 수 있다(UNIQUE active_employee_id)
        long 새요청 = 요청을_넣는다();
        assertThat(row(새요청).get("sync_state")).isEqualTo("QUEUED");
    }

    @Test
    void POST_시간초과여도_요청_전후_목록_차이가_1개면_그_checkId를_연결한다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list("CHK-old"))          // 요청 전
            .createReturns(FakeBackgroundCheckApi.timeout())
            .listReturns(FakeBackgroundCheckApi.list("CHK-old", "CHK-new"));  // 요청 후

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("check_id")).isEqualTo("CHK-new");
        assertThat(api.createCalls()).hasSize(1);                        // 재POST하지 않는다
    }

    @Test
    void 새_후보가_2개면_추측하지_않고_확인_필요로_둔다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list("CHK-old"))
            .createReturns(FakeBackgroundCheckApi.timeout())
            .listReturns(FakeBackgroundCheckApi.list("CHK-old", "CHK-a", "CHK-b"));

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("SUBMIT_UNKNOWN");
        assertThat(row.get("check_id")).isNull();
        assertThat(((Number) row.get("last_diff_count")).intValue()).isEqualTo(2);
        assertThat(row.get("next_run_at")).isNull();                     // 자동으로 더 하지 않는다
    }

    @Test
    void 요청_전_목록_조회가_실패했으면_요청_후_목록을_보지_않고_확인_필요로_둔다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.httpFailure(503, Duration.ofSeconds(30)))  // 요청 전 실패
            .createReturns(FakeBackgroundCheckApi.timeout());

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("SUBMIT_UNKNOWN");
        assertThat(row.get("pre_list_check_ids")).isNull();               // 비교 기준 없음
        assertThat(api.listCalls()).hasSize(1);                           // 요청 후 목록은 부르지 않았다
        assertThat(api.createCalls()).hasSize(1);                         // POST는 그대로 보냈다
    }

    @Test
    void SUBMITTING으로_남은_행을_복구할_때_POST를_다시_보내지_않는다() {
        long id = 요청을_넣는다();
        // 작업자가 POST 결과를 기록하기 전에 멈춘 상황: SUBMITTING이고 선점이 풀린 상태
        jdbc.update("""
            UPDATE background_check
               SET sync_state = 'SUBMITTING', post_sent_at = UTC_TIMESTAMP(6),
                   poll_deadline_at = TIMESTAMPADD(SECOND, 180, UTC_TIMESTAMP(6)),
                   pre_list_check_ids = CAST('["CHK-old"]' AS JSON),
                   claim_token = NULL, next_run_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
             WHERE id = ?""", id);
        api.listReturns(FakeBackgroundCheckApi.list("CHK-old", "CHK-recovered"));

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(api.createCalls()).isEmpty();                          // 재POST 없음
        assertThat(row.get("sync_state")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("check_id")).isEqualTo("CHK-recovered");
    }

    @Test
    void 우리_DB가_이미_쓰는_checkId는_새_후보에서_제외한다() {
        long 먼저 = 요청을_넣는다();
        jdbc.update("""
            UPDATE background_check SET sync_state='DONE', check_id='CHK-mine', result_status='clear',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", 먼저);
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())                    // 요청 전: 빈 목록
            .createReturns(FakeBackgroundCheckApi.timeout())
            .listReturns(FakeBackgroundCheckApi.list("CHK-mine"));        // 요청 후: 우리가 이미 쓰는 것뿐

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("SUBMIT_UNKNOWN");
        assertThat(((Number) row.get("last_diff_count")).intValue()).isZero();
    }

    @Test
    void 다시_조회를_누르면_결과_미확인_건에_GET을_1회만_보낸다() {
        long id = 요청을_넣는다();
        jdbc.update("""
            UPDATE background_check SET sync_state='UNRESOLVED', check_id='CHK-9', result_status='pending',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", id);
        assertThat(store.requestRecheck(id)).isEqualTo(1);
        assertThat(store.requestRecheck(id)).isZero();                    // 두 번 눌러도 한 번만 예약된다
        api.getReturns(FakeBackgroundCheckApi.detail("CHK-9", ResultStatus.FLAGGED));

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("DONE");
        assertThat(row.get("result_status")).isEqualTo("flagged");
        assertThat(row.get("recheck_requested_at")).isNull();
        assertThat(api.getCalls()).hasSize(1);
    }

    @Test
    void 다시_조회가_또_실패하면_결과_미확인에_머문다() {
        long id = 요청을_넣는다();
        jdbc.update("""
            UPDATE background_check SET sync_state='UNRESOLVED', check_id='CHK-10', result_status='pending',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", id);
        store.requestRecheck(id);
        api.getReturns(FakeBackgroundCheckApi.httpFailure(503, Duration.ofSeconds(30)));

        worker.tick();

        Map<String, Object> row = row(id);
        assertThat(row.get("sync_state")).isEqualTo("UNRESOLVED");
        assertThat(row.get("next_run_at")).isNull();                      // 자동 반복하지 않는다
        assertThat(api.getCalls()).hasSize(1);
    }

    @Test
    void 생성_안_됨으로_종료하면_같은_직원의_새_요청이_가능해진다() {
        long id = 요청을_넣는다();
        jdbc.update("""
            UPDATE background_check SET sync_state='SUBMIT_UNKNOWN', post_sent_at=UTC_TIMESTAMP(6),
                   next_run_at=NULL WHERE id = ?""", id);

        assertThat(store.closeAsNotCreated(id)).isEqualTo(1);

        assertThat(row(id).get("sync_state")).isEqualTo("SUBMIT_FAILED");
        assertThat(row(id).get("last_error_code")).isEqualTo("UNKNOWN");
        long 새요청 = 요청을_넣는다();
        assertThat(row(새요청).get("sync_state")).isEqualTo("QUEUED");
    }

    @Test
    void 마감까지_남은_시간이_짧으면_GET_타임아웃을_그만큼_줄인다() {
        long id = 요청을_넣는다();
        api.listReturns(FakeBackgroundCheckApi.list())
            .createReturns(FakeBackgroundCheckApi.created("CHK-11", ResultStatus.PENDING))
            .getReturns(FakeBackgroundCheckApi.pending("CHK-11"));
        worker.tick();
        // 마감이 10초 뒤인 상황
        jdbc.update("UPDATE background_check SET poll_deadline_at = TIMESTAMPADD(SECOND, 10, UTC_TIMESTAMP(6)), "
            + "next_run_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        worker.tick();

        List<Duration> timeouts = api.getTimeouts();
        assertThat(timeouts).hasSize(1);
        assertThat(timeouts.get(0)).isLessThanOrEqualTo(Duration.ofSeconds(11));
        assertThat(timeouts.get(0)).isLessThan(properties.getTimeout());
    }
}
