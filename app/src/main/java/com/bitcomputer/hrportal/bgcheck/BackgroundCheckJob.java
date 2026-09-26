package com.bitcomputer.hrportal.bgcheck;

import java.time.LocalDate;

/**
 * 워커가 한 단계를 처리하는 데 필요한 값만 담은 읽기 전용 스냅샷.
 *
 * <p>엔티티를 쓰지 않는 이유: 워커는 트랜잭션 밖에서 돌기 때문에(외부 호출을 기다리는 동안 DB 연결을 잡지 않으려고)
 * 엔티티의 지연 로딩 필드({@code employee})를 읽는 순간 실패한다. 실제로 그렇게 만들었다가 테스트에서
 * {@code LazyInitializationException}으로 잡혔다. 필요한 값은 조회 시점에 한 번에 읽는다.
 *
 * @param preListCheckIdsJson 요청 전 목록 JSON 원문. <b>null이면 "요청 전 목록 조회 실패"</b>(비교 기준 없음)
 * @param deadlineSecondsRemaining 자동 확인 마감까지 남은 초. <b>DB가 계산한다</b>(UTC_TIMESTAMP 기준) —
 *        자바 시계와 DB 시계를 섞어 빼면 드라이버 시간대 설정에 따라 값이 어긋난다(실제로 테스트에서 걸렸다).
 *        마감이 없으면 null, 이미 지났으면 음수
 */
public record BackgroundCheckJob(
    long id,
    SyncState syncState,
    String checkId,
    ResultStatus resultStatus,
    String empNo,
    String sentFamilyName,
    String sentGivenName,
    LocalDate sentBirthDate,
    String preListCheckIdsJson,
    int consecutiveFailures,
    Long deadlineSecondsRemaining,
    boolean recheckRequested,
    String lastErrorCode) {
}
