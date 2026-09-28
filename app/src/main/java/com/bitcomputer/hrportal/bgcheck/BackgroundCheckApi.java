package com.bitcomputer.hrportal.bgcheck;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 외부 Background Check API(swagger.yaml)를 부르는 창구.
 *
 * <p>인터페이스로 둔 이유: 워커 로직을 실제 외부 호출 없이 검증하기 위해서다(DESIGN.md 2.5.6).
 * 테스트는 가짜 구현으로 바꿔 "503 뒤 재시도", "POST 시간 초과" 같은 상황을 재현한다.
 *
 * <p>구현은 예외를 던지지 않고 {@link ApiResult}로 결과를 돌려준다. 실패도 정상적인 관측 결과이기 때문이다.
 */
public interface BackgroundCheckApi {

    /** POST /background-checks. 타임아웃·오류가 나도 <b>자동으로 다시 보내지 않는다</b>(7-2). */
    ApiResult<CreatedCheck> create(CreateRequest request, Duration timeout);

    /** GET /background-checks/{checkId} */
    ApiResult<CheckDetail> get(String checkId, Duration timeout);

    /** GET /background-checks?employeeId=... — 요청 전후 목록 비교에만 쓴다. checkId 목록만 꺼낸다. */
    ApiResult<List<String>> listCheckIds(String employeeId, Duration timeout);

    /**
     * POST 요청 본문. 외부 API는 firstName/lastName을 요구하므로 사내 한글 성명을 나눠 보낸다.
     * 나눈 결과는 직원 레코드에 저장된 값을 그대로 쓴다(규칙으로 자르지 않는다, DESIGN.md 7장).
     */
    record CreateRequest(String employeeId, String firstName, String lastName, LocalDate dateOfBirth) {
    }

    /** POST 201 응답. 상세 항목은 들어 있지 않아 상세 GET이 따로 필요하다(DESIGN.md 6.4). */
    record CreatedCheck(String checkId, String employeeId, ResultStatus status, Instant createdAt) {
    }

    /**
     * GET 200 응답. 명세에 없는 필드 중 tenant·tenantEmployee는 의미를 모르므로 담지 않는다(DESIGN.md 4.3).
     *
     * @param hasDetail 상세 항목(criminalRecord 등)이 응답에 실제로 들어 있었는가
     * @param processingMs 명세에 없는 필드. 실측에서 pending 응답의 값이 최종 (completedAt − createdAt)과
     *                     24/24 일치했다(MEASUREMENTS.md 4장). 다음 조회 시각 계산에만 쓰고 저장하지 않는다(10장).
     *                     없거나 0 이상의 정수가 아니면 null
     */
    record CheckDetail(String checkId, ResultStatus status, Boolean criminalRecord, Boolean educationVerified,
                       Boolean employmentVerified, String creditScore, Instant createdAt, Instant completedAt,
                       boolean hasDetail, Long processingMs) {
    }
}
