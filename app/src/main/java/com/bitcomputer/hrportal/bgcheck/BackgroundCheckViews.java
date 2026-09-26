package com.bitcomputer.hrportal.bgcheck;

import java.time.Instant;

/**
 * 화면·상태 API에 내려보내는 조회 전용 값(DESIGN.md 5.2, 6.4).
 *
 * <p>엔티티를 그대로 넘기지 않는 이유는 두 가지다.
 * ① 이 앱은 {@code open-in-view: false}라 화면을 그릴 때 지연 로딩이 실패한다(직원 상세에서 실제로 겪었다).
 * ② 민감 상세(범죄기록·신용등급 등)를 상세 화면에서만 다루려면, 목록·상태 API에는 <b>담기지 않는 타입</b>이 필요하다.
 */
public final class BackgroundCheckViews {

    private BackgroundCheckViews() {
    }

    /** 이력 목록 한 줄. 민감 상세는 없다. */
    public record Summary(
        Long id,
        Instant requestedAt,
        SyncState syncState,
        ResultStatus resultStatus,
        String checkId,
        String requestedByLoginId) {

        public String syncLabel() {
            return syncState.label();
        }

        public String resultLabel() {
            return resultStatus == null ? "-" : resultStatus.label();
        }
    }

    /** 상세 화면. 민감 상세는 이 화면에서만 보여 준다. */
    public record Detail(
        Long id,
        String empNo,
        String employeeName,
        Instant requestedAt,
        String requestedByLoginId,
        SyncState syncState,
        ResultStatus resultStatus,
        String checkId,
        Boolean criminalRecord,
        Boolean educationVerified,
        Boolean employmentVerified,
        String creditScore,
        Instant externalCreatedAt,
        Instant completedAt,
        Integer pollCount,
        Integer consecutiveFailures,
        String lastErrorCode,
        Instant lastErrorAt,
        Integer lastDiffCount,
        boolean preListMissing,
        Instant recheckRequestedAt,
        Instant detailPurgedAt) {

        public String syncLabel() {
            return syncState.label();
        }

        public String resultLabel() {
            return resultStatus == null ? "-" : resultStatus.label();
        }

        /** "다시 조회"를 보여 줄 수 있는 상태인가(DESIGN.md 5.2). */
        public boolean canRecheck() {
            if (recheckRequestedAt != null) {
                return false;
            }
            return syncState == SyncState.UNRESOLVED
                || (syncState == SyncState.SUBMIT_UNKNOWN && !preListMissing);
        }

        /** "생성 안 됨으로 종료"를 보여 줄 수 있는 상태인가. */
        public boolean canCloseUnknown() {
            return syncState == SyncState.SUBMIT_UNKNOWN;
        }

        public boolean hasDetailValues() {
            return criminalRecord != null || educationVerified != null || employmentVerified != null
                || creditScore != null;
        }
    }

    /**
     * 화면 자동 갱신용 상태(JSON). <b>민감 상세를 넣지 않는다.</b>
     * 이 요청은 DB만 읽고 외부 API를 부르지 않는다.
     */
    public record Status(
        Long id,
        String syncState,
        String syncLabel,
        String resultStatus,
        String resultLabel,
        Long nextRunInSeconds,
        Integer pollCount,
        Integer consecutiveFailures,
        String lastErrorCode,
        boolean active) {
    }
}
