package com.bitcomputer.hrportal.bgcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.bitcomputer.hrportal.db.MySqlIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 작업을 한 작업자만 가져가는지, 늦게 돌아온 작업자가 새 결과를 덮어쓰지 못하는지 확인한다(DESIGN.md 2.5.4, 2.5.6).
 *
 * <p>이 검증이 중요한 이유: 배포할 때 새 버전과 옛 버전이 잠깐 함께 도는 순간 워커가 둘이 된다.
 * 그때 같은 행을 둘 다 집으면 POST가 중복되어 <b>외부에 조회 기록이 두 개</b> 생긴다.
 * 실측에서 같은 employeeId로 POST하면 매번 새 checkId가 만들어졌다(MEASUREMENTS.md 4장, n=5).
 *
 * <p>실제 MySQL에 스레드 2개가 동시에 선점 UPDATE를 보내고, 성공한 수의 합이 항상 1인지 50회 반복한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundCheckClaimTest extends MySqlIntegrationTest {

    private static final int REPEATS = 50;

    @Autowired BackgroundCheckStore store;

    private long adminAccountId;
    private long employeeId;

    @BeforeEach
    void 준비한다() {
        adminAccountId = insertAdminAccount("bgcheck-claim-admin");
        employeeId = employeeId("EMP-005");
    }

    @AfterEach
    void 정리한다() {
        jdbc.update("DELETE FROM background_check WHERE requested_by_id = ?", adminAccountId);
        jdbc.update("DELETE FROM account WHERE id = ?", adminAccountId);
    }

    private long 요청을_넣는다() {
        return store.insertQueued(employeeId, adminAccountId, "김", "솔", LocalDate.of(1992, 12, 30));
    }

    @Test
    void 두_작업자가_동시에_선점해도_한_명만_성공한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < REPEATS; i++) {
                long id = 요청을_넣는다();
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> first = pool.submit(() -> {
                    start.await();
                    return store.claim(id, SyncState.QUEUED, UUID.randomUUID().toString(), 15);
                });
                Future<Integer> second = pool.submit(() -> {
                    start.await();
                    return store.claim(id, SyncState.QUEUED, UUID.randomUUID().toString(), 15);
                });
                start.countDown();

                int succeeded = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
                assertThat(succeeded)
                    .as("%d번째 시도: 동시에 선점한 작업자 수", i + 1)
                    .isEqualTo(1);
                // 같은 직원은 진행 중 조회를 1건만 가질 수 있으므로(UNIQUE active_employee_id),
                // 다음 회차를 위해 이번 행을 지운다.
                jdbc.update("DELETE FROM background_check WHERE id = ?", id);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 선점하지_못한_작업자는_결과를_쓸_수_없다() {
        long id = 요청을_넣는다();
        String winner = UUID.randomUUID().toString();
        String loser = UUID.randomUUID().toString();
        assertThat(store.claim(id, SyncState.QUEUED, winner, 15)).isEqualTo(1);

        // 선점하지 못한(또는 선점이 만료된) 작업자의 늦은 쓰기는 0행이라 버려진다.
        assertThat(store.markSubmitFailed(id, loser, "HTTP_400")).isZero();
        assertThat(store.recordTransientFailure(id, loser, "HTTP_500", 20)).isZero();
        assertThat(store.markUnresolved(id, loser, "HTTP_404")).isZero();

        assertThat(jdbc.queryForObject("SELECT sync_state FROM background_check WHERE id = ?", String.class, id))
            .isEqualTo("QUEUED");
    }

    @Test
    void 우리_POST가_받은_checkId는_선점이_끝난_뒤에도_기록한다() {
        // DESIGN.md 2.5.4의 예외: 우리가 직접 받은 checkId를 버리면 외부에 만든 기록을 잃는다.
        long id = 요청을_넣는다();
        String token = UUID.randomUUID().toString();
        store.claim(id, SyncState.QUEUED, token, 15);
        store.markSubmitting(id, token, "[]", 180);
        // 선점이 만료되어 다른 작업자가 가져간 상황을 만든다
        jdbc.update("UPDATE background_check SET claim_token = 'other-worker' WHERE id = ?", id);

        assertThat(store.linkCheckId(id, "CHK-late", ResultStatus.PENDING, null, 35)).isEqualTo(1);

        assertThat(jdbc.queryForMap("SELECT sync_state, check_id FROM background_check WHERE id = ?", id))
            .containsEntry("sync_state", "IN_PROGRESS")
            .containsEntry("check_id", "CHK-late");
    }

    @Test
    void 이미_checkId가_있으면_다른_값으로_덮어쓰지_않는다() {
        long id = 요청을_넣는다();
        String token = UUID.randomUUID().toString();
        store.claim(id, SyncState.QUEUED, token, 15);
        store.markSubmitting(id, token, "[]", 180);
        assertThat(store.linkCheckId(id, "CHK-first", ResultStatus.PENDING, null, 35)).isEqualTo(1);

        assertThat(store.linkCheckId(id, "CHK-second", ResultStatus.PENDING, null, 35)).isZero();

        assertThat(jdbc.queryForObject("SELECT check_id FROM background_check WHERE id = ?", String.class, id))
            .isEqualTo("CHK-first");
    }

    @Test
    void 처리할_때가_안_된_행은_선점되지_않는다() {
        long id = 요청을_넣는다();
        jdbc.update("UPDATE background_check SET next_run_at = TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(6)) "
            + "WHERE id = ?", id);

        assertThat(store.claim(id, SyncState.QUEUED, UUID.randomUUID().toString(), 15)).isZero();
        assertThat(store.findDueIds(10)).doesNotContain(id);
    }

    @Test
    void 진행_중인_조회가_있으면_같은_직원으로_새_요청을_넣을_수_없다() {
        요청을_넣는다();

        // 최종 방어는 DB의 UNIQUE(active_employee_id)다. 앱의 사전 확인만으로는 동시 클릭을 막지 못한다.
        assertRejectedBy("uq_bgcheck_active_employee", this::요청을_넣는다);
    }

    @Test
    void 끝난_조회는_같은_직원의_새_요청을_막지_않는다() {
        long id = 요청을_넣는다();
        jdbc.update("""
            UPDATE background_check SET sync_state='DONE', check_id='CHK-done', result_status='clear',
                   post_sent_at=UTC_TIMESTAMP(6), next_run_at=NULL WHERE id = ?""", id);

        long 새요청 = 요청을_넣는다();

        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM background_check WHERE requested_by_id = ? ORDER BY id", Long.class, adminAccountId);
        assertThat(ids).containsExactly(id, 새요청);
    }
}
