package com.bitcomputer.hrportal.bgcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link BackgroundCheckWorker#tick()}을 주기적으로 부른다(DESIGN.md 2.5.2).
 *
 * <p>워커와 스케줄러를 나눈 이유: 테스트에서는 이 빈만 꺼 두고({@code bgcheck.worker.enabled=false})
 * {@code tick()}을 직접 부른다. 그래야 시간을 기다리지 않고 단계별 동작을 확인할 수 있다.
 *
 * <p>{@code fixedDelay}는 "이전 실행이 끝난 뒤 일정 시간 쉬고 다시"라는 뜻이라, 기본 스케줄러 스레드 1개에서
 * tick이 자기 자신과 겹치지 않는다.
 */
@Component
@ConditionalOnProperty(name = "bgcheck.worker.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundCheckScheduler.class);

    private final BackgroundCheckWorker worker;
    private final BgCheckProperties properties;

    public BackgroundCheckScheduler(BackgroundCheckWorker worker, BgCheckProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${bgcheck.tick-delay}")
    public void tick() {
        if (!properties.hasCandidateKey()) {
            // 키가 없으면 외부 호출을 할 수 없다. 조용히 넘어가면 원인을 찾기 어려우므로 한 번씩 남긴다.
            log.warn("환경변수 CANDIDATE_KEY가 없어 Background Check 워커가 외부 호출을 건너뛴다");
            return;
        }
        int processed = worker.tick();
        if (processed > 0) {
            log.info("Background Check {}건을 처리했다", processed);
        }
    }
}
