package com.bitcomputer.hrportal.bgcheck;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Background Check 연동 설정(DESIGN.md 8.3).
 *
 * <p>타이밍 값은 <b>MEASUREMENTS.md 7~12장에 기입한 결정값을 그대로 옮긴 것</b>이다. 여기서 새 값을 만들지 않는다.
 * 근거(섹션·수치·n)는 7~12장에 있다. 배포 위치에서 다시 측정해 값이 바뀌면 `application.yml`만 고치면 된다.
 *
 * <p>{@code candidateKey}는 환경변수 {@code CANDIDATE_KEY}에서만 온다. 로그·DB·화면에 남기지 않는다.
 *
 * @param baseUrl 외부 API 주소
 * @param candidateKey 인증키(환경변수에서만 주입)
 * @param getTimeout GET 1회 타임아웃 — 7-1: 35초
 * @param postTimeout POST 1회 타임아웃 — 7-2: 5초
 * @param firstPollDelay pending일 때 첫 GET까지 — 10장: POST 후 35초
 * @param pollInterval 이후 폴링 간격이자 Retry-After가 없을 때의 재시도 간격 — 9·10장: 20초
 * @param maxWait POST 시점부터의 자동 확인 마감 — 11장: 180초
 * @param maxConsecutiveFailures GET 일시적 실패 연속 상한 — 8장: 8회
 * @param tickDelay 워커 실행 간격 [미정→제안]. 9장의 20초 간격보다 충분히 짧아야 실제 호출이 늦어지지 않는다
 * @param leaseMargin 선점 시간 = 이번 호출 타임아웃 + 이 여유 [미정→제안]
 * @param batchSize 한 tick에 처리할 최대 건수 [제안] — 6장 동시성 측정 참고
 */
@ConfigurationProperties(prefix = "bgcheck")
public record BgCheckProperties(
    String baseUrl,
    String candidateKey,
    Duration getTimeout,
    Duration postTimeout,
    Duration firstPollDelay,
    Duration pollInterval,
    Duration maxWait,
    int maxConsecutiveFailures,
    Duration tickDelay,
    Duration leaseMargin,
    int batchSize) {

    public boolean hasCandidateKey() {
        return candidateKey != null && !candidateKey.isBlank();
    }
}
