package com.bitcomputer.hrportal.bgcheck;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 API 호출을 맡는 예약 작업(DESIGN.md 2.5, 6.4).
 *
 * <p>화면 요청은 {@code QUEUED} 행을 INSERT만 하고 바로 응답한다. 실제 호출은 여기서 한다.
 * 실측에서 정상 GET도 최대 29.7초가 걸렸고 순차 GET의 59.1%가 실패였다(MEASUREMENTS.md 2·8.1장).
 * 그런 호출을 화면 요청 안에서 기다리면 관리자 화면이 멈춘다.
 *
 * <p>한 번의 {@link #tick()}에서 한 행은 <b>한 단계</b>만 진행한다: 선점 → 외부 호출 1회 → token 조건 기록.
 * 진행 상태는 모두 DB에 있으므로 서버가 재시작해도 이어서 처리된다.
 *
 * <p>이 클래스는 상태를 직접 UPDATE하지 않는다. 모든 쓰기는 {@link BackgroundCheckStore}를 통한다.
 */
@Component
public class BackgroundCheckWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundCheckWorker.class);

    /** 상세 GET인데 pending이 돌아온 경우(관측 0건). 결과를 덮어쓰지 않고 재시도한다(DESIGN.md 6.4). */
    private static final String ERROR_DETAIL_PENDING = "DETAIL_PENDING";
    private static final String ERROR_WORKER_LOST = "WORKER_LOST";

    private final BackgroundCheckStore store;
    private final BackgroundCheckApi api;
    private final BgCheckProperties properties;
    private final ObjectMapper objectMapper;

    public BackgroundCheckWorker(BackgroundCheckStore store, BackgroundCheckApi api,
                                 BgCheckProperties properties, ObjectMapper objectMapper) {
        this.store = store;
        this.api = api;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 처리할 때가 된 행을 한 묶음 처리한다.
     *
     * <p>테스트에서는 스케줄러를 끄고 이 메서드를 직접 부른다(DESIGN.md 2.5.6). 그래서 시간을 기다리지 않고도
     * "35초 뒤로 예약됐는가", "연속 8회면 중단하는가"를 확인할 수 있다.
     *
     * @return 이번에 처리한 행 수
     */
    public int tick() {
        int expired = store.expireDeadlines();
        if (expired > 0) {
            log.info("자동 확인 마감이 지난 조회 {}건을 '결과 미확인'으로 정리했다", expired);
        }
        List<Long> dueIds = store.findDueIds(properties.batchSize());
        int processed = 0;
        for (Long id : dueIds) {
            try {
                if (processOne(id)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // 한 건의 실패가 나머지 처리를 막지 않게 한다. 행은 선점 시간이 지나면 다시 처리된다.
                log.warn("조회 {} 처리 중 예외: {}", id, e.toString());
            }
        }
        return processed;
    }

    private boolean processOne(long id) {
        BackgroundCheckJob check = store.findJob(id).orElse(null);
        if (check == null) {
            return false;
        }
        return switch (check.syncState()) {
            case QUEUED -> submit(check);
            // 작업자가 POST 도중 멈춘 행. 재POST하지 않고 생성 여부 확인 경로로만 간다(DESIGN.md 2.5.5).
            case SUBMITTING -> recoverSubmitting(check);
            case SUBMIT_UNKNOWN -> recheckSubmitUnknown(check);
            case IN_PROGRESS -> pollOrFetchDetail(check);
            case UNRESOLVED -> recheckUnresolved(check);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- 제출 단계

    /** QUEUED → (요청 전 목록) → SUBMITTING 커밋 → POST 1회. */
    private boolean submit(BackgroundCheckJob check) {
        String token = newToken();
        if (store.claim(check.id(), SyncState.QUEUED, token, leaseSeconds(properties.postTimeout())) == 0) {
            return false;
        }
        String empNo = check.empNo();

        // 요청 전 목록: 실패해도 POST는 그대로 보낸다. 실패는 SQL NULL로 남겨 "비교 기준 없음"을 구분한다.
        ApiResult<List<String>> preList = api.listCheckIds(empNo, properties.getTimeout());
        String preListJson = preList.isSuccess() ? toJson(preList.body()) : null;

        store.extendLease(check.id(), token, leaseSeconds(properties.postTimeout()));
        if (store.markSubmitting(check.id(), token, preListJson, properties.maxWait().toSeconds()) == 0) {
            return false;
        }

        ApiResult<BackgroundCheckApi.CreatedCheck> created = api.create(
            new BackgroundCheckApi.CreateRequest(empNo, check.sentGivenName(), check.sentFamilyName(),
                check.sentBirthDate()),
            properties.postTimeout());

        if (created.isSuccess()) {
            linkCreated(check.id(), created.body());
            return true;
        }
        if (created.httpStatus() == 400 || created.httpStatus() == 401) {
            // 입력 오류·키 오류는 외부에 생성되지 않았음이 분명하다.
            store.markSubmitFailed(check.id(), token, created.errorCodeOrHttp());
            return true;
        }
        // 시간 초과·네트워크 오류·그 밖의 응답: 생성 여부를 모른다. 재POST하지 않고 목록으로 확인한다.
        compareListsOrMarkUnknown(check, token, created.errorCodeOrHttp(), preList.isSuccess() ? preList.body() : null);
        return true;
    }

    /** POST 결과를 기록하기 전에 작업자가 멈춘 행. 재POST 없이 요청 후 목록만 본다. */
    private boolean recoverSubmitting(BackgroundCheckJob check) {
        String token = newToken();
        if (store.claim(check.id(), SyncState.SUBMITTING, token, leaseSeconds(properties.getTimeout())) == 0) {
            return false;
        }
        log.info("조회 {}: SUBMITTING 상태로 남아 있던 행을 복구한다(재POST 없음)", check.id());
        compareListsOrMarkUnknown(check, token, ERROR_WORKER_LOST, parsePreList(check));
        return true;
    }

    /** 관리자가 "다시 조회"를 누른 확인 필요 건: 요청 후 목록을 1회 더 본다. */
    private boolean recheckSubmitUnknown(BackgroundCheckJob check) {
        if (!check.recheckRequested()) {
            return false;
        }
        String token = newToken();
        if (store.claim(check.id(), SyncState.SUBMIT_UNKNOWN, token, leaseSeconds(properties.getTimeout())) == 0) {
            return false;
        }
        compareListsOrMarkUnknown(check, token, check.lastErrorCode(), parsePreList(check));
        return true;
    }

    /**
     * 요청 전후 목록 비교(DESIGN.md 4.3).
     * 새 후보 = 요청 후 목록 − 요청 전 목록 − 우리 DB의 다른 행이 이미 쓰는 checkId.
     * <b>정확히 1개일 때만</b> 연결한다. 0개·2개 이상·목록 조회 실패는 추측하지 않고 '확인 필요'로 둔다.
     */
    private void compareListsOrMarkUnknown(BackgroundCheckJob check, String token, String errorCode,
                                           List<String> preList) {
        if (preList == null) {
            // 요청 전 목록이 없으면 비교 기준이 없다. 요청 후 목록을 조회하지 않는다.
            store.markSubmitUnknown(check.id(), token, errorCode, null);
            return;
        }
        store.extendLease(check.id(), token, leaseSeconds(properties.getTimeout()));
        ApiResult<List<String>> postList =
            api.listCheckIds(check.empNo(), properties.getTimeout());
        if (!postList.isSuccess()) {
            store.markSubmitUnknown(check.id(), token, postList.errorCodeOrHttp(), null);
            return;
        }
        Set<String> candidates = new HashSet<>(postList.body());
        preList.forEach(candidates::remove);
        store.findLinkedCheckIds(List.copyOf(candidates)).forEach(candidates::remove);

        if (candidates.size() == 1) {
            String checkId = candidates.iterator().next();
            log.info("조회 {}: 요청 전후 목록 차이가 1개라 checkId를 연결한다", check.id());
            // 결과 상태는 아직 모른다. pending으로 두고 폴링해서 확인한다.
            store.linkCheckId(check.id(), checkId, ResultStatus.PENDING, null,
                properties.pollInterval().toSeconds());
            return;
        }
        log.info("조회 {}: 새 후보 {}개라 연결하지 않는다(확인 필요)", check.id(), candidates.size());
        store.markSubmitUnknown(check.id(), token, errorCode, candidates.size());
    }

    private void linkCreated(long id, BackgroundCheckApi.CreatedCheck created) {
        // POST 응답에는 상세 항목이 없다(실측: responseFields). clear/flagged여도 상세 GET을 한 번 더 보낸다.
        // pending이면 11장대로 35초 뒤 첫 폴링, 즉시 최종이면 지금 바로 상세 GET(0초 뒤).
        long delay = created.status() == ResultStatus.PENDING ? properties.firstPollDelay().toSeconds() : 0;
        store.linkCheckId(id, created.checkId(), created.status(), created.createdAt(), delay);
    }

    // ---------------------------------------------------------------- 조회 단계

    /** IN_PROGRESS: result_status가 pending이면 폴링, clear/flagged이면 상세 항목을 받는 GET이다. */
    private boolean pollOrFetchDetail(BackgroundCheckJob check) {
        String token = newToken();
        Duration timeout = getTimeoutWithin(check);
        if (store.claim(check.id(), SyncState.IN_PROGRESS, token, leaseSeconds(timeout)) == 0) {
            return false;
        }
        boolean detailFetch = check.resultStatus() != null && check.resultStatus().isFinal();
        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get(check.checkId(), timeout);

        if (result.isSuccess()) {
            BackgroundCheckApi.CheckDetail detail = result.body();
            if (detail.status().isFinal() && detail.hasDetail()) {
                store.applyFinalDetail(check.id(), token, detail.status(), detail.criminalRecord(),
                    detail.educationVerified(), detail.employmentVerified(), detail.creditScore(),
                    detail.createdAt(), detail.completedAt());
                return true;
            }
            if (detailFetch) {
                // 상세를 받으러 갔는데 상세가 없다(또는 pending). 결과를 덮어쓰지 않고 재시도 규칙을 적용한다.
                failOrGiveUp(check, token, ERROR_DETAIL_PENDING, null);
                return true;
            }
            store.applyPending(check.id(), token, properties.pollInterval().toSeconds());
            return true;
        }
        if (result.httpStatus() == 401 || result.httpStatus() == 404) {
            // 반복해도 결과가 같다(11장 비재시도 코드).
            store.markUnresolved(check.id(), token, result.errorCodeOrHttp());
            return true;
        }
        failOrGiveUp(check, token, result.errorCodeOrHttp(), result.retryAfter());
        return true;
    }

    /** 관리자가 "다시 조회"를 누른 결과 미확인 건: GET 1회만 보낸다. */
    private boolean recheckUnresolved(BackgroundCheckJob check) {
        if (!check.recheckRequested() || check.checkId() == null) {
            return false;
        }
        String token = newToken();
        if (store.claim(check.id(), SyncState.UNRESOLVED, token, leaseSeconds(properties.getTimeout())) == 0) {
            return false;
        }
        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get(check.checkId(), properties.getTimeout());
        if (result.isSuccess() && result.body().status().isFinal() && result.body().hasDetail()) {
            BackgroundCheckApi.CheckDetail detail = result.body();
            store.applyFinalDetail(check.id(), token, detail.status(), detail.criminalRecord(),
                detail.educationVerified(), detail.employmentVerified(), detail.creditScore(),
                detail.createdAt(), detail.completedAt());
            return true;
        }
        // 1회만 시도한다. 여전히 확인되지 않으면 '결과 미확인'에 머문다.
        store.markUnresolved(check.id(), token,
            result.isSuccess() ? ERROR_DETAIL_PENDING : result.errorCodeOrHttp());
        return true;
    }

    /**
     * 일시적 실패 처리. 연속 실패가 11장 상한(8회)에 닿으면 '결과 미확인'으로 중단한다.
     * 다음 시도 시각은 Retry-After(헤더 → body) → 없으면 폴링 간격이다.
     */
    private void failOrGiveUp(BackgroundCheckJob check, String token, String errorCode, Duration retryAfter) {
        int failures = check.consecutiveFailures() + 1;
        if (failures >= properties.maxConsecutiveFailures()) {
            log.info("조회 {}: 일시적 실패 연속 {}회 → 결과 미확인으로 중단", check.id(), failures);
            store.markUnresolved(check.id(), token, errorCode);
            return;
        }
        long delay = retryAfter != null ? retryAfter.toSeconds() : properties.pollInterval().toSeconds();
        store.recordTransientFailure(check.id(), token, errorCode, delay);
    }

    /**
     * GET 타임아웃 = min(11장 35초, 마감까지 남은 시간). 마감을 넘겨 기다리지 않는다.
     * 남은 시간은 DB가 계산한 값을 쓴다(자바 시계와 DB 시계를 섞지 않는다).
     */
    private Duration getTimeoutWithin(BackgroundCheckJob check) {
        Duration timeout = properties.getTimeout();
        Long remainingSeconds = check.deadlineSecondsRemaining();
        if (remainingSeconds == null) {
            return timeout;
        }
        if (remainingSeconds <= 0) {
            return Duration.ofSeconds(1);
        }
        Duration remaining = Duration.ofSeconds(remainingSeconds);
        return remaining.compareTo(timeout) < 0 ? remaining : timeout;
    }

    private long leaseSeconds(Duration callTimeout) {
        return callTimeout.plus(properties.leaseMargin()).toSeconds();
    }

    private String newToken() {
        return UUID.randomUUID().toString();
    }

    /** 요청 전 목록 JSON. null이면 "조회 실패"라 비교 기준이 없다는 뜻이다. */
    private List<String> parsePreList(BackgroundCheckJob check) {
        if (check.preListCheckIdsJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(check.preListCheckIdsJson(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(List<String> checkIds) {
        try {
            return objectMapper.writeValueAsString(checkIds);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
