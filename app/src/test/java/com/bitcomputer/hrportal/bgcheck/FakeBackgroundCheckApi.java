package com.bitcomputer.hrportal.bgcheck;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 워커 검증용 가짜 외부 API(DESIGN.md 2.5.6).
 *
 * <p>실제 API는 같은 요청에도 200·500·503·타임아웃이 섞여 돌아온다(실측 2-4). 그래서 "이번에는 이 응답"을
 * 미리 넣어 두고 순서대로 돌려준다. 호출 횟수도 세어 "재POST하지 않았는가", "요청 후 목록을 보지 않았는가"를 확인한다.
 */
public class FakeBackgroundCheckApi implements BackgroundCheckApi {

    private final Deque<ApiResult<CreatedCheck>> createResponses = new ArrayDeque<>();
    private final Deque<ApiResult<CheckDetail>> getResponses = new ArrayDeque<>();
    private final Deque<ApiResult<List<String>>> listResponses = new ArrayDeque<>();

    private final List<CreateRequest> createCalls = new ArrayList<>();
    private final List<String> getCalls = new ArrayList<>();
    private final List<String> listCalls = new ArrayList<>();
    private final List<Duration> getTimeouts = new ArrayList<>();

    // ---- 응답 준비 ----

    public FakeBackgroundCheckApi createReturns(ApiResult<CreatedCheck> result) {
        createResponses.add(result);
        return this;
    }

    public FakeBackgroundCheckApi getReturns(ApiResult<CheckDetail> result) {
        getResponses.add(result);
        return this;
    }

    public FakeBackgroundCheckApi listReturns(ApiResult<List<String>> result) {
        listResponses.add(result);
        return this;
    }

    public static ApiResult<CreatedCheck> created(String checkId, ResultStatus status) {
        return ApiResult.success(201,
            new CreatedCheck(checkId, "EMP", status, Instant.parse("2026-09-22T12:00:00Z")), Duration.ofMillis(60));
    }

    public static ApiResult<CheckDetail> detail(String checkId, ResultStatus status) {
        return ApiResult.success(200, new CheckDetail(checkId, status, false, true, true, "good",
            Instant.parse("2026-09-22T12:00:00Z"), Instant.parse("2026-09-22T12:00:54Z"), true, 54_000L),
            Duration.ofMillis(400));
    }

    /** processingMs가 없는 pending(필드가 사라진 경우). */
    public static ApiResult<CheckDetail> pending(String checkId) {
        return pending(checkId, null);
    }

    /** 실측처럼 processingMs가 함께 오는 pending. */
    public static ApiResult<CheckDetail> pending(String checkId, Long processingMs) {
        return ApiResult.success(200, new CheckDetail(checkId, ResultStatus.PENDING, null, null, null, null,
            Instant.parse("2026-09-22T12:00:00Z"), null, false, processingMs), Duration.ofMillis(300));
    }

    public static <T> ApiResult<T> httpFailure(int status, Duration retryAfter) {
        return ApiResult.failure(status, "HTTP_" + status, retryAfter, Duration.ofMillis(40));
    }

    public static <T> ApiResult<T> timeout() {
        return ApiResult.failure(0, ApiResult.ERROR_TIMEOUT, null, Duration.ofSeconds(5));
    }

    public static ApiResult<List<String>> list(String... checkIds) {
        return ApiResult.success(200, List.of(checkIds), Duration.ofMillis(200));
    }

    // ---- 호출 기록 ----

    public List<CreateRequest> createCalls() {
        return createCalls;
    }

    public List<String> getCalls() {
        return getCalls;
    }

    public List<String> listCalls() {
        return listCalls;
    }

    public List<Duration> getTimeouts() {
        return getTimeouts;
    }

    // ---- BackgroundCheckApi ----

    @Override
    public ApiResult<CreatedCheck> create(CreateRequest request, Duration timeout) {
        createCalls.add(request);
        if (createResponses.isEmpty()) {
            throw new IllegalStateException("준비한 POST 응답이 없다. 테스트에서 createReturns(...)로 넣어라.");
        }
        return createResponses.poll();
    }

    @Override
    public ApiResult<CheckDetail> get(String checkId, Duration timeout) {
        getCalls.add(checkId);
        getTimeouts.add(timeout);
        if (getResponses.isEmpty()) {
            throw new IllegalStateException("준비한 GET 응답이 없다. 테스트에서 getReturns(...)로 넣어라.");
        }
        return getResponses.poll();
    }

    @Override
    public ApiResult<List<String>> listCheckIds(String employeeId, Duration timeout) {
        listCalls.add(employeeId);
        if (listResponses.isEmpty()) {
            throw new IllegalStateException("준비한 목록 응답이 없다. 테스트에서 listReturns(...)로 넣어라.");
        }
        return listResponses.poll();
    }
}
