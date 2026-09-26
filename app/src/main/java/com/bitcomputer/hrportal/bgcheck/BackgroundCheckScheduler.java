package com.bitcomputer.hrportal.bgcheck;

import jakarta.annotation.PostConstruct;
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
    /** 키가 없다는 경고를 tick마다 찍지 않기 위한 표시. */
    private boolean warnedMissingKey;

    public BackgroundCheckScheduler(BackgroundCheckWorker worker, BgCheckProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    /** 시작할 때 상태를 남긴다. "요청했는데 아무 일도 안 일어난다"의 원인을 로그 첫머리에서 찾을 수 있게 한다. */
    @PostConstruct
    void logStartupState() {
        if (properties.hasCandidateKey()) {
            log.info("Background Check 워커 시작: {}초마다 확인한다", properties.tickDelay().toSeconds());
        } else {
            log.warn("환경변수 CANDIDATE_KEY가 없다. 배경 조회 요청은 저장되지만 외부 호출은 하지 않는다 "
                + "(요청이 '요청 대기'에서 멈춘다). 실행 구성에 CANDIDATE_KEY를 넣고 다시 시작하라.");
        }
    }

    @Scheduled(fixedDelayString = "${bgcheck.tick-delay}")
    public void tick() {
        if (!properties.hasCandidateKey()) {
            if (!warnedMissingKey) {
                log.warn("CANDIDATE_KEY가 없어 Background Check 외부 호출을 건너뛴다(이 경고는 한 번만 남긴다)");
                warnedMissingKey = true;
            }
            return;
        }
        int processed = worker.tick();
        if (processed > 0) {
            log.info("Background Check {}건을 처리했다", processed);
        }
    }
}
