package com.bitcomputer.hrportal.bgcheck;

import java.time.Duration;

/**
 * 외부 API 호출 1회의 결과(DESIGN.md 4.3, 6.4).
 *
 * <p>업무 결과({@code body})와 연동 결과(HTTP 상태·오류 종류)를 한 값에 담되 <b>섞지 않는다</b>.
 * 실측에서 순차 GET의 59.1%가 500·503·타임아웃이었다(MEASUREMENTS.md 8.1장, n=413).
 * 실패 응답이 결과 컬럼을 덮어쓰면 "조회 실패"가 "이상 있음"처럼 보이므로, 워커는 {@link #isSuccess()}일 때만
 * 결과를 반영한다.
 *
 * @param httpStatus 응답 코드. 응답을 받지 못했으면 0
 * @param body 2xx일 때만 값이 있다
 * @param errorCode 실패 종류(HTTP_500, HTTP_503, TIMEOUT, NETWORK, HTTP_400 …). 성공이면 null
 * @param retryAfter 503이 알려 준 대기 시간(헤더 → body 순서로 읽는다). 없으면 null
 * @param elapsed 호출에 걸린 시간(로그용)
 */
public record ApiResult<T>(int httpStatus, T body, String errorCode, Duration retryAfter, Duration elapsed) {

    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_NETWORK = "NETWORK";
    public static final String ERROR_BAD_RESPONSE = "BAD_RESPONSE";

    public static <T> ApiResult<T> success(int httpStatus, T body, Duration elapsed) {
        return new ApiResult<>(httpStatus, body, null, null, elapsed);
    }

    public static <T> ApiResult<T> failure(int httpStatus, String errorCode, Duration retryAfter, Duration elapsed) {
        return new ApiResult<>(httpStatus, null, errorCode, retryAfter, elapsed);
    }

    public boolean isSuccess() {
        return body != null && httpStatus >= 200 && httpStatus < 300;
    }

    /** 다시 시도할 수 있는 실패인가(5xx·타임아웃·네트워크). 400/401/404는 반복해도 결과가 같다(11장). */
    public boolean isTransientFailure() {
        if (isSuccess()) {
            return false;
        }
        if (httpStatus >= 500) {
            return true;
        }
        return ERROR_TIMEOUT.equals(errorCode) || ERROR_NETWORK.equals(errorCode)
            || ERROR_BAD_RESPONSE.equals(errorCode);
    }

    /** 응답은 받았지만 4xx인 경우. POST면 SUBMIT_FAILED(400·401), GET이면 UNRESOLVED(401·404)로 간다. */
    public boolean isClientError() {
        return httpStatus >= 400 && httpStatus < 500;
    }

    public String errorCodeOrHttp() {
        if (errorCode != null) {
            return errorCode;
        }
        return httpStatus > 0 ? "HTTP_" + httpStatus : "UNKNOWN";
    }
}
