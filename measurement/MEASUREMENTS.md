# MEASUREMENTS — Background Check API 실측 결과

> 이 문서는 `measure_background_api.py report` 가 `data/*.csv` 만 읽어 자동 생성한다. 생성 시각: 2026-09-22T15:42:22.196Z
> 측정하지 않은 값은 기재하지 않으며, 표본이 없거나 부족하면 `TBD` / `표본 부족` 으로 표시한다.

## 1. 측정 목적 및 환경

- **목적**: swagger.yaml 에 적히지 않은 실제 거동(지연, 오류 분포, 처리시간, 503/Retry-After, 중복 요청, 동시성)을 측정해 타임아웃·재시도·폴링 정책의 근거로 사용한다.
- **대상 API**: https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com
- **명세**: `swagger.yaml` version 1.1.0
- **측정 기간 (자동 측정, UTC)**: 2026-09-22T12:37:50.615Z ~ 2026-09-22T14:56:42.598Z
- **측정 클라이언트 Python**: 3.12.10 / 측정 위치: 단일 클라이언트(지원자 PC)
- **원시 데이터**: `data/measurements.csv` 549행(요청 1건 = 1행), `data/lifecycles.csv` 40행 → checkId 기준 31건(중단·재개 시 최신 행 사용)
- **저장 범위**: 요청/응답 본문은 저장하지 않는다. 식별자·시각·상태·숫자 필드와 필드 *이름* 목록만 기록하며, 인증키·이름·생년월일·tenant/tenantEmployee 의 실제 값은 CSV·콘솔·이 문서에 남기지 않는다.
- **지연 측정 방식**: 클라이언트에서 요청 직전~응답 수신 완료까지 `time.perf_counter()` 로 측정한 wall-clock(ms). 네트워크 왕복 포함.
- **백분위수 계산**: nearest-rank — 정렬된 표본 x₁..xₙ 에서 pQ = x⌈Q·n⌉ (보간 없음, 항상 실제 관측값). 예) n=100 이면 p50 = 50번째, p95 = 95번째, p99 = 99번째로 작은 값. 모든 통계 옆에 n 을 표기하고, p95 는 n<20, p99 는 n<100 이면 `표본 부족` 을 붙인다.
- **p99 불확실성**: n=100 이어도 p99 는 사실상 상위 1~2개 관측값으로 정해진다. 2.1 의 95% 신뢰구간(분포 가정 없는 순서통계량 방법)으로 보면 n=100 에서 p99 의 상한은 관측 최대값을 넘어 **데이터로 제한되지 않는다**. 따라서 p99 는 '관측된 꼬리'로만 해석하고 단일 확정값으로 쓰지 않는다.
- **용어 구분**: `httpStatus`(200/201/503 …)는 전송 계층 결과, `apiStatus`(pending/clear/flagged)는 2xx 응답 body 의 업무 상태다. 두 값은 별도 컬럼으로 기록하며 섞지 않는다.
- **수동 관찰**: `data/manual_observations.csv` 에 별도 기록(3건)하고 **부록 A** 에만 표시한다. 1~9장의 모든 통계는 자동 측정만으로 계산한다.

측정 실행 이력 (`data/runs.csv`):

| runId | 시작(UTC) | 명령 | 주요 파라미터 |
|---|---|---|---|
| 20260922T123750Z-f11c | 2026-09-22T12:37:50.613Z | lifecycle | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, samples=1 |
| 20260922T124300Z-ca0c | 2026-09-22T12:43:00.434Z | lifecycle | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, samples=10 |
| 20260922T125705Z-d1cd | 2026-09-22T12:57:05.762Z | resume | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False |
| 20260922T125816Z-570d | 2026-09-22T12:58:16.554Z | latency | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, samples=20, interval=0.2 |
| 20260922T130850Z-d959 | 2026-09-22T13:08:50.176Z | latency | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, samples=100, interval=0.2 |
| 20260922T135448Z-4c65 | 2026-09-22T13:54:48.162Z | duplicate | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, employee=EMP-001, count=5, interval=1.0 |
| 20260922T135641Z-8313 | 2026-09-22T13:56:41.697Z | errors | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False |
| 20260922T140439Z-8b07 | 2026-09-22T14:04:39.395Z | concurrency | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, levels=1,5,10, requests_per_level=20, pause=5.0 |
| 20260922T141220Z-17e3 | 2026-09-22T14:12:20.681Z | lifecycle | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, samples=20 |
| 20260922T144019Z-b0cc | 2026-09-22T14:40:20.079Z | resume | timeout=30.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False |
| 20260922T144804Z-a86d | 2026-09-22T14:48:04.907Z | concurrency | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, levels=1,5,10, requests_per_level=20, pause=5.0 |
| 20260922T145531Z-a02f | 2026-09-22T14:55:31.295Z | list | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False, employee=EMP-001, interval=1.0 |
| 20260922T145625Z-2127 | 2026-09-22T14:56:25.996Z | errors | timeout=90.0, poll_interval=2.0, max_wait=300.0, max_retry_wait=120.0, no_wait_on_503=False |

## 2. GET /background-checks/{checkId} 응답 지연

### 2.1 HTTP 200 응답만 (정상 응답 지연)

| 구분 | n | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|---|
| latency 테스트 (완료된 checkId 순차 조회) | 114 | 56 (n=114) | 393 (n=114) | 26,398 (n=114) | 29,414 (n=114) | 29,725 (n=114) |
| lifecycle 폴링 중 조회 | 55 | 97 (n=55) | 478 (n=55) | 26,998 (n=55) | 28,539 (n=55, 표본 부족: n≥100 필요) | 28,539 (n=55) |
| 동시성 테스트 (전 단계 합산) | 34 | 120 (n=34) | 1,004 (n=34) | 28,210 (n=34) | 29,776 (n=34, 표본 부족: n≥100 필요) | 29,776 (n=34) |
| 전체 (자동 측정 합산) | 203 | 56 (n=203) | 458 (n=203) | 26,636 (n=203) | 29,414 (n=203) | 29,776 (n=203) |

백분위수 순위와 95% 신뢰구간 (전체 HTTP 200, n=203; 순서통계량 기반, 분포 가정 없음):

| 통계 | nearest-rank 순위 | 값 (ms) | 95% 신뢰구간 (ms, 순위) |
|---|---|---|---|
| p50 | 102번째 / 203 | 458 (n=203) | [414 (88번째), 499 (116번째)] |
| p95 | 193번째 / 203 | 26,636 (n=203) | [23,682 (186번째), 28,539 (199번째)] |
| p99 | 201번째 / 203 | 29,414 (n=203) | [28,499 (198번째), 관측 범위 밖(상한 없음)] |

### 2.2 HTTP 상태코드별 지연 (자동 측정 전체)

| 구분 | n | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|---|
| HTTP 200 | 203 | 56 (n=203) | 458 (n=203) | 26,636 (n=203) | 29,414 (n=203) | 29,776 (n=203) |
| HTTP 401 | 2 | 30 (n=2) | 30 (n=2) | 34 (n=2, 표본 부족: n≥20 필요) | 34 (n=2, 표본 부족: n≥100 필요) | 34 (n=2) |
| HTTP 404 | 2 | 7,358 (n=2) | 7,358 (n=2) | 10,664 (n=2, 표본 부족: n≥20 필요) | 10,664 (n=2, 표본 부족: n≥100 필요) | 10,664 (n=2) |
| HTTP 500 | 173 | 19 (n=173) | 30 (n=173) | 517 (n=173) | 642 (n=173) | 661 (n=173) |
| HTTP 503 | 115 | 20 (n=115) | 39 (n=115) | 30,023 (n=115) | 30,361 (n=115) | 30,588 (n=115) |
| HTTP EXC:ReadTimeout | 5 | 30,006 (n=5) | 30,008 (n=5) | 30,015 (n=5, 표본 부족: n≥20 필요) | 30,015 (n=5, 표본 부족: n≥100 필요) | 30,015 (n=5) |

### 2.3 업무 상태별 지연 (HTTP 200 중 apiStatus 기준)

| 구분 | n | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|---|
| apiStatus=clear | 179 | 56 (n=179) | 465 (n=179) | 27,338 (n=179) | 29,725 (n=179) | 29,776 (n=179) |
| apiStatus=pending | 24 | 120 (n=24) | 424 (n=24) | 17,477 (n=24) | 26,998 (n=24, 표본 부족: n≥100 필요) | 26,998 (n=24) |

### 2.4 참고: POST /background-checks 지연 (HTTP 201)

| 구분 | n | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|---|
| POST 201 | 36 | 29 (n=36) | 58 (n=36) | 859 (n=36) | 1,895 (n=36, 표본 부족: n≥100 필요) | 1,895 (n=36) |

- 네트워크 예외(타임아웃 포함, `EXC:*`): 7 / 549 (1.3%) — 종류: {'EXC:ReadTimeout': 7}

## 3. HTTP 상태코드별 건수와 비율

### 3.1 자동 측정 전체

| httpStatus | 건수 / 전체 (비율) |
|---|---|
| 200 | 204 / 549 (37.2%) |
| 201 | 36 / 549 (6.6%) |
| 400 | 5 / 549 (0.9%) |
| 401 | 2 / 549 (0.4%) |
| 404 | 2 / 549 (0.4%) |
| 500 | 177 / 549 (32.2%) |
| 503 | 116 / 549 (21.1%) |
| EXC:ReadTimeout | 7 / 549 (1.3%) |

### 3.2 테스트 유형 × 엔드포인트별

| testType | endpoint | n | 200 | 201 | 400 | 401 | 404 | 500 | 503 | EXC:ReadTimeout |
|---|---|---|---|---|---|---|---|---|---|---|
| concurrency | GET /background-checks/{checkId} | 80 | 34 / 80 (42.5%) | 0 | 0 | 0 | 0 | 29 / 80 (36.2%) | 17 / 80 (21.2%) | 0 |
| concurrency-probe | GET /background-checks/{checkId} | 1 | 0 | 0 | 0 | 0 | 0 | 1 / 1 (100.0%) | 0 | 0 |
| duplicate | POST /background-checks | 5 | 0 | 5 / 5 (100.0%) | 0 | 0 | 0 | 0 | 0 | 0 |
| duplicate-list | GET /background-checks | 6 | 0 | 0 | 0 | 0 | 0 | 3 / 6 (50.0%) | 1 / 6 (16.7%) | 2 / 6 (33.3%) |
| errors | GET /background-checks | 2 | 0 | 0 | 1 / 2 (50.0%) | 0 | 0 | 1 / 2 (50.0%) | 0 | 0 |
| errors | GET /background-checks/{checkId} | 6 | 0 | 0 | 0 | 2 / 6 (33.3%) | 2 / 6 (33.3%) | 1 / 6 (16.7%) | 1 / 6 (16.7%) | 0 |
| errors | POST /background-checks | 4 | 0 | 0 | 4 / 4 (100.0%) | 0 | 0 | 0 | 0 | 0 |
| latency | GET /background-checks/{checkId} | 274 | 114 / 274 (41.6%) | 0 | 0 | 0 | 0 | 98 / 274 (35.8%) | 62 / 274 (22.6%) | 0 |
| lifecycle | GET /background-checks/{checkId} | 139 | 55 / 139 (39.6%) | 0 | 0 | 0 | 0 | 44 / 139 (31.7%) | 35 / 139 (25.2%) | 5 / 139 (3.6%) |
| lifecycle | POST /background-checks | 31 | 0 | 31 / 31 (100.0%) | 0 | 0 | 0 | 0 | 0 | 0 |
| list-check | GET /background-checks | 1 | 1 / 1 (100.0%) | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

### 3.3 GET /{checkId} HTTP 200 응답의 업무 상태(apiStatus) 분포 — HTTP 상태와 별개

| apiStatus | 건수 / HTTP 200 전체 |
|---|---|
| clear | 179 / 203 (88.2%) |
| pending | 24 / 203 (11.8%) |

## 4. 동일 employeeId 반복 POST 결과

| runId | employeeId | POST 수 | HTTP 코드 | apiStatus | 고유 checkId / 성공 | 목록 totalCount 전 | 후 | 증가 | 생성 checkId 중 목록 포함 |
|---|---|---|---|---|---|---|---|---|---|
| 20260922T135448Z-4c65 | EMP-001 | 5 | {'201': 5} | {'clear': 4, 'pending': 1} | 5 / 5 |  |  |  | 5 / 5 |

관측 사실:
- runId `20260922T135448Z-4c65`: 'after' 목록 조회 실패 → 이후 재조회(runId `20260922T145531Z-a02f`, totalCount 6)로 목록 포함 여부만 확인. 전/후 증가분은 'before' 가 없으므로 계산하지 않음
- runId `20260922T135448Z-4c65`: 생성된 checkId 5개 중 목록에 나타난 수 5
- runId `20260922T135448Z-4c65`: 성공한 POST 5건 모두 서로 다른 checkId 가 발급됨 (동일 employeeId 중복 거부/병합이 관측되지 않음, n=5)
- runId `20260922T135448Z-4c65`: 목록 조회 전/후 중 HTTP 200 을 받지 못한 쪽이 있어 totalCount 비교 불가 (TBD)

## 5. pending → clear/flagged 처리시간

- lifecycle 자동 측정 건수(checkId 기준): 31 — completed 31, timeout 0, incomplete(중단) 0, error 0
- **미완료 건은 처리시간 통계에서 제외**: 최종 상태 미도달 0건
- 최종 상태 분포 (completed 기준): clear 31, flagged 0 (n=31)
- POST 응답이 즉시 최종 상태였던 건수: 18 / 31 (58.1%)

- 완료 31건 = pending 경유 13건 + POST 즉시 최종 18건. 두 경로의 분포가 다르므로 나눠서 표시한다.

| 구분 | n | min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|---|---|
| [pending 경유] 서버 기준: completedAt − createdAt | 13 | 34,043 (n=13) | 55,313 (n=13) | 85,401 (n=13, 표본 부족: n≥20 필요) | 85,401 (n=13, 표본 부족: n≥100 필요) | 85,401 (n=13) |
| [pending 경유] 응답 필드 processingMs (명세에 없음) | 13 | 34,043 (n=13) | 55,313 (n=13) | 85,401 (n=13, 표본 부족: n≥20 필요) | 85,401 (n=13, 표본 부족: n≥100 필요) | 85,401 (n=13) |
| [pending 경유] 클라이언트: POST 전송 → 최종 상태 첫 관측 | 13 | 53,284 (n=13) | 96,376 (n=13) | 149,332 (n=13, 표본 부족: n≥20 필요) | 149,332 (n=13, 표본 부족: n≥100 필요) | 149,332 (n=13) |
| [pending 경유] 클라이언트: POST 전송 → 마지막 pending 관측 | 13 | 58 (n=13) | 33,939 (n=13) | 81,434 (n=13, 표본 부족: n≥20 필요) | 81,434 (n=13, 표본 부족: n≥100 필요) | 81,434 (n=13) |
| [POST 즉시 최종] 서버 기준: completedAt − createdAt | 18 | 0 (n=18) | 0 (n=18) | 0 (n=18, 표본 부족: n≥20 필요) | 0 (n=18, 표본 부족: n≥100 필요) | 0 (n=18) |
| [POST 즉시 최종] 클라이언트: POST 응답 시간 | 18 | 35 (n=18) | 57 (n=18) | 600 (n=18, 표본 부족: n≥20 필요) | 600 (n=18, 표본 부족: n≥100 필요) | 600 (n=18) |
| [전체 완료] 서버 기준: completedAt − createdAt | 31 | 0 (n=31) | 0 (n=31) | 84,245 (n=31) | 85,401 (n=31, 표본 부족: n≥100 필요) | 85,401 (n=31) |

- 1차 근거는 서버 시각 기반 `completedAt − createdAt` 이다(폴링 간격의 영향을 받지 않음). 클라이언트 관측값은 폴링 간격·503 대기만큼 늦게 잡히며, 실제 완료 시점은 '마지막 pending 관측'과 '최종 상태 첫 관측' 사이에 있다. resume 으로 확인한 건은 클라이언트 관측값을 기록하지 않는다.
- processingMs 와 (completedAt − createdAt) 일치 여부 (자동): 완전 일치 31 / 31 (100.0%), |차이|≤1ms 31 / 31 (100.0%), 최대 |차이| 0ms
- POST 201 응답의 estimatedCompletionSeconds: 관찰됨 0건 / 관찰되지 않음 31건
- 폴링 중 503 을 1회 이상 받은 lifecycle: 13 / 31 (41.9%)
- pending 응답(HTTP 200) 중 processingMs 가 이미 존재: 24 / 24 (100.0%); 그 값이 같은 checkId 의 최종 (completedAt − createdAt) 과 같음: 24 / 24 (100.0%) (checkId 11개) — 명세에 없는 필드이므로 의미는 확정하지 않음
- 서버 createdAt − 클라이언트 POST 요청 시작 시각: min -7,564 ms, max -6,515 ms (n=36). 음수이면 서버 시각이 클라이언트 시계보다 이르다는 뜻 → 클라이언트 시각과 서버 시각을 직접 빼서 처리시간을 계산하지 않는다
- 최종 상태 관측 후 다시 pending 이 관측된 사례: 0건 (n=31) — 단, 최종 상태 관측 시 폴링을 멈추므로 이후 변화는 측정 범위 밖

관측된 상태 시퀀스 (상위 10개, `*n` 은 연속 횟수):

| statusSequence | 건수 |
|---|---|
| clear | 18 |
| pending>500>pending*3>500>EXC>pending>503>EXC>500>EXC>clear | 1 |
| pending>500>503>500>clear | 1 |
| pending>503>pending*2>503>500*2>pending>503>clear | 1 |
| pending>EXC>503>500>EXC>clear | 1 |
| pending*2>500>503>500>clear | 1 |
| pending>503>500*2>pending>503>clear | 1 |
| pending*2>500>pending>503>pending>500>pending*3>503*2>500*3>503>clear | 1 |
| pending*2>500*4>503*2>500>clear | 1 |
| pending>503>pending>503>clear | 1 |

## 6. 동시 요청 수 증가에 따른 변화

| method | 동시 요청 수 | 요청 n | 성공(200/201) | HTTP 503 | 기타(4xx/5xx/예외) | 성공 p50 (ms) | 성공 p95 (ms) | 성공 p99 (ms) | 성공 max (ms) |
|---|---|---|---|---|---|---|---|---|---|
| GET | 1 | 40 | 19 / 40 (47.5%) | 11 / 40 (27.5%) | 10 / 40 (25.0%) | 519 (n=19) | 26,636 (n=19, 표본 부족: n≥20 필요) | 26,636 (n=19, 표본 부족: n≥100 필요) | 26,636 (n=19) |
| GET | 5 | 20 | 10 / 20 (50.0%) | 3 / 20 (15.0%) | 7 / 20 (35.0%) | 1,060 (n=10) | 29,776 (n=10, 표본 부족: n≥20 필요) | 29,776 (n=10, 표본 부족: n≥100 필요) | 29,776 (n=10) |
| GET | 10 | 20 | 5 / 20 (25.0%) | 3 / 20 (15.0%) | 12 / 20 (60.0%) | 1,004 (n=5) | 23,770 (n=5, 표본 부족: n≥20 필요) | 23,770 (n=5, 표본 부족: n≥100 필요) | 23,770 (n=5) |

단계 진행 판단 기록 (`runs.csv` 의 concurrency-decision):

| runId | 단계 | 503 비율 | 이전 단계 503 비율 | 5xx/예외 비율 | 중단 기준 | Retry-After 최대(s) | 대기(s) | 대기 후 확인 요청 | 판단 |
|---|---|---|---|---|---|---|---|---|---|
| 20260922T140439Z-8b07 | 1 | 0.25 | None | 0.4 | (이전 규칙) | 30 | 30.0 | 500 | 중단: Retry-After/대기 후에도 회복 안 됨 |
| 20260922T144804Z-a86d | 1 | 0.3 | None | 0.65 | 0.9 | 30 | 30.0 |  | 진행: 503 비율 증가 없음 (Retry-After 만큼 대기함) |
| 20260922T144804Z-a86d | 5 | 0.15 | 0.3 | 0.5 | 0.9 | 30 | 30.0 |  | 진행: 503 비율 증가 없음 (Retry-After 만큼 대기함) |
| 20260922T144804Z-a86d | 10 | 0.15 | 0.15 | 0.75 | 0.9 | 30 |  |  | 마지막 단계 |

- 동시성 테스트 중 관측된 retryAfter 값: {'30': 8}
- 규칙: 기본은 완료된 checkId 에 대한 GET(레코드 생성 없음). 각 단계 요청은 재시도하지 않고 1회 결과만 기록. 단계 사이 `--pause` 만큼 대기하고, Retry-After 를 받으면 그 값만큼 대기(상한 `--max-retry-wait`). 5xx/예외 비율이 첫 단계보다 `--abort-margin`(기본 0.3) 이상 늘거나 절대 상한 `--abort-error-ratio`(기본 0.9)를 넘으면 중단. 503 비율이 이전 단계보다 늘었다면 대기 후 확인 GET(최대 3회)으로 회복을 확인하고, 모두 실패하면 중단. POST 동시성은 `--method post` 로만 실행되며 총 요청 수 상한이 있다.
- 참고: '(이전 규칙)' 행은 첫 버전의 안전장치(확인 요청 1회 실패 시 중단)로 실행된 결과다. 이 API 는 동시성 1 에서도 5xx 가 흔해 평소 실패를 과부하로 판단하고 1단계에서 멈췄다 → 규칙을 기준선 비교로 수정.

## 7. Swagger 명세와 실제 동작 비교

### 7.1 HTTP 상태코드: 명세 정의 vs 실제 관측 (자동 측정)

| operation | 명세 정의 코드 | 실제 관측 (코드×건수) | 명세에 없는데 관측됨 | 명세에 있으나 미관측 |
|---|---|---|---|---|
| POST /background-checks | 201, 400 | 201×36, 400×4 | 없음 | 없음 |
| GET /background-checks | 200, 400, 500, 503 | 200×1, 400×1, 500×4, 503×1, EXC:ReadTimeout×2 | 없음 | 없음 |
| GET /background-checks/{checkId} | 200, 404, 500, 503 | 200×203, 401×2, 404×2, 500×173, 503×115, EXC:ReadTimeout×5 | 401×2 | 없음 |

### 7.2 응답 필드: 명세 스키마 vs 실제 응답 (자동 측정)

- 값은 비교하지 않고 **필드 이름**만 비교한다.
- `명세에 없으나 응답에 있음`: 발견 사실만 기록하며 의미는 확정하지 않는다.
- `이번 응답에서 관찰되지 않음`: 명세 스키마에 있으나 `required` 로 지정되지 않은 필드가 응답에 없던 경우. **명세 위반으로 보지 않는다.** (이 swagger 의 응답 스키마에는 `required` 목록이 없다 → 응답 필드는 모두 선택적)
- `required 필드 누락`: 스키마 `required` 에 있는데 없던 경우만 '명세 위반 후보'로 따로 표시한다.
- `pending 중 미관찰(명세상 예상)`: 명세 설명에 '완료 시에만 존재'로 적힌 필드가 pending 응답에서 없던 경우.

| operation | HTTP | 응답 n | 명세 스키마 | 명세에 없으나 응답에 있음 | 이번 응답에서 관찰되지 않음 (required 아님) | pending 중 미관찰(명세상 예상) | required 필드 누락 (명세 위반 후보) |
|---|---|---|---|---|---|---|---|
| GET /background-checks | 200 | 1 | BackgroundCheckList | 없음 | 없음 | - | 없음 |
| GET /background-checks | 400 | 1 | ErrorResponse | 없음 | 없음 | - | 없음 |
| GET /background-checks | 500 | 4 | ErrorResponse | 없음 | 없음 | - | 없음 |
| GET /background-checks | 503 | 1 | ServiceUnavailableResponse | 없음 | 없음 | - | 없음 |
| GET /background-checks/{checkId} | 200 | 203 | BackgroundCheckResult | processingMs (203/203), tenant (203/203), tenantEmployee (203/203) | 없음 | - | 없음 |
| GET /background-checks/{checkId} | 401 | 2 | 명세에 해당 응답 정의 없음 (비교 불가) | 없음 | 없음 | - | 없음 |
| GET /background-checks/{checkId} | 404 | 2 | ErrorResponse | 없음 | 없음 | - | 없음 |
| GET /background-checks/{checkId} | 500 | 173 | ErrorResponse | 없음 | 없음 | - | 없음 |
| GET /background-checks/{checkId} | 503 | 115 | ServiceUnavailableResponse | 없음 | error (44/115), retryAfter (44/115), statusCode (44/115) | - | 없음 |
| POST /background-checks | 201 | 36 | BackgroundCheckCreated | 없음 | estimatedCompletionSeconds (36/36) | - | 없음 |
| POST /background-checks | 400 | 4 | ErrorResponse | 없음 | 없음 | - | 없음 |

명세에 응답 스키마가 없는 코드에서 관측된 필드 구성:

| operation | HTTP | 응답 필드 | 건수 |
|---|---|---|---|
| GET /background-checks/{checkId} | 401 | error;message;statusCode | 2 |

### 7.3 명세 값 vs 실측 값

- `estimatedCompletionSeconds`: 명세 예시값 20초, '폴링 주기 결정에 사용하라'고 기술(required 아님). 실측: 자동 측정 POST 201 응답 중 이 필드가 관찰되지 않음 36 / 36 (100.0%). 관찰되지 않았으므로 폴링 주기의 근거로 쓸 수 없다. 실제 서버 처리시간 p50 0 (n=31) ms, max 85,401 (n=31) ms.
- 503 `Retry-After` 헤더: 명세는 헤더 제공을 기술. 실측: 자동 503 116건 중 헤더 존재 0건

### 7.4 명세 문서 자체의 불일치 (swagger.yaml 정적 분석)

- 해석되지 않는 `$ref`: `#/components/schemas/Error` (위치 `#/components/responses/Unauthorized/content/application/json/schema`)
- 어떤 경로에서도 참조되지 않는 응답 정의: `components/responses/Unauthorized`
- `POST /background-checks` 에 401 응답이 정의되어 있지 않음 (description 에는 '키가 없으면 401' 기술)
- `GET /background-checks` 에 401 응답이 정의되어 있지 않음 (description 에는 '키가 없으면 401' 기술)
- `GET /background-checks/{checkId}` 에 401 응답이 정의되어 있지 않음 (description 에는 '키가 없으면 401' 기술)
- `POST /background-checks` 에 500/503 응답이 정의되어 있지 않음
- `POST /background-checks` 의 동일 employeeId 반복 요청 동작은 명세에 기술되어 있지 않음 → 4장 실측 참조

### 7.5 오류 케이스 (errors 명령): 명세 기대값 vs 관측

> 각 케이스는 실행 1회당 1건이다. 이 API 는 평소에도 GET 의 173 / 500 (34.6%) 가 500 이므로, 오류 케이스에서 500 을 받았다면 케이스 고유의 응답인지 평소 실패인지 구분할 수 없다(재실행으로 확인 필요).

| 케이스 (명세 기대값) | 시도 n | 관측 HTTP | 관측 message |
|---|---|---|---|
| 존재하지 않는 checkId (명세: 404) | 2 | 500×1, 503×1 | Service temporarily unavailable. Please try again later. / The service is currently overloaded. Please retry after the specified time. |
| X-Candidate-Key 헤더 없음 (명세 설명: 401) | 2 | 401×2 | *** / Missing or malformed X-Candidate-Key header. Use the key provided in your assignment email. |
| 잘못된 키 (명세 설명: 401) | 2 | 404×2 | Background check not found: CHK-00000000-0000-0000-0000-000000000000 |
| employeeId 쿼리 누락 (명세: 400) | 2 | 500×1, 400×1 | Missing required query parameter: employeeId / Service temporarily unavailable. Please try again later. |
| dateOfBirth 누락 — EMP-007 상황 (명세: 400). 수락되면 레코드가 생성됨 | 2 | 400×2 | Missing required field: dateOfBirth |
| dateOfBirth 빈 문자열 (명세상 format: date). 수락되면 레코드가 생성됨 | 2 | 400×2 | Missing required field: dateOfBirth |

- 주의: '잘못된 키' 케이스는 잘못된 키와 **존재하지 않는 checkId 를 함께** 보냈다. 따라서 404 가 키 때문인지 checkId 때문인지 이 시험만으로 구분할 수 없고, '잘못된 키는 401 이 아니다'라고 단정하지 않는다. (구분하려면 유효한 checkId + 잘못된 키 조합이 필요하다.)

## 8. 503 및 Retry-After 관찰 결과

- 자동 측정 503: 116 / 549 (21.1%)
  - testType 별: {'lifecycle': 35, 'latency': 62, 'duplicate-list': 1, 'concurrency': 17, 'errors': 1}
- `Retry-After` 헤더: 존재 0 / 116 (0.0%), 값 분포 없음
- body `retryAfter`: 존재 72 / 116 (62.1%), 값 분포 {'30': 72}
- 헤더와 body 둘 다 있는 503 중 값 일치: TBD (n=0)
- 503 이외 5xx: {'500': 177}
- 순차 요청(lifecycle/latency)에서 503 직후 같은 checkId 재요청 결과: {'EXC:ReadTimeout': 1, '500': 34, '200': 46, '503': 15} (n=96)
  - 503 → 다음 요청 간격(s): min 30.0, max 32.4 (n=96; 측정 스크립트는 기본적으로 Retry-After 만큼 대기 후 재요청, --no-wait-on-503 실행분은 폴링/요청 간격으로 재요청)
- 연속 503 길이 분포: {1: 68, 2: 13, 3: 1} (연속 구간 n=82, 최대 3)

### 8.0 503 응답 유형 (응답 body 의 필드 구성별)

> 같은 503 이라도 body 구성과 응답시간이 다르면 다른 경로에서 생성됐을 수 있으므로 나눠서 본다. 원인(어느 계층이 생성했는지)은 확정하지 않는다.

| body 필드 | message | 건수 | Retry-After 제공 | 지연 min (ms) | 지연 p50 (ms) | 지연 max (ms) |
|---|---|---|---|---|---|---|
| error, message, retryAfter, statusCode | The service is currently overloaded. Please retry after the specified time. | 72 | 72 / 72 (100.0%) | 20 (n=72) | 28 (n=72) | 513 (n=72) |
| message | Service Unavailable | 44 | 0 / 44 (0.0%) | 30,008 (n=44) | 30,013 (n=44) | 30,588 (n=44) |

- 측정용 타임아웃을 30s 보다 길게 둔 실행(7회, GET 455건)에서: 29s 이상 걸린 비정상 응답 {'503': 43}, 29s 이상 걸린 200 3건, 네트워크 예외 0건. (30s 타임아웃 실행에서 끊긴 요청이 같은 응답이었는지는 직접 관측하지 않았다)

### 8.1 503 이외의 일시적 실패 (5xx 전체 + 네트워크 예외/타임아웃)

- 순차 GET 요청 중 일시적 실패: 244 / 413 (59.1%) — 종류 {'500': 142, 'EXC:ReadTimeout': 5, '503': 97}
- 503 이외 일시적 실패 중 Retry-After(헤더/바디) 제공: 0 / 184 (0.0%)
- 503 이외 5xx 응답 메시지: {'Service temporarily unavailable. Please try again later.': 177}
- 일시적 실패 직후 같은 checkId 재요청 결과: {'200': 99, 'EXC:ReadTimeout': 4, '500': 86, '503': 52} (n=241)
- 연속 일시적 실패 길이 분포: {1: 45, 2: 19, 4: 5, 3: 19, 5: 5, 7: 3, 6: 5, 8: 1} (연속 구간 n=102, 최대 8)
- ReadTimeout 은 측정용 타임아웃(`--timeout`, runs.csv 참조)에서 끊은 것이다. 그 이후에 응답이 왔을지는 관측하지 못했다(우측 절단 데이터, n=7).

## 9. 측정 결과 기반 timeout / retry / polling 결정 영역

> 아래 표는 **근거 수치**만 자동으로 채운다. 결정값은 자동으로 정하지 않으며, 근거 표본이 기준에 미달하면 `TBD` 로 표시한다. 최종 결정은 11장(수기 블록)에 근거와 함께 기입한다.

| 항목 | 근거 수치 (n 포함) | 판단 기준 | 자동 판정 |
|---|---|---|---|
| GET 타임아웃 | HTTP 200 지연 p99 29,414 (n=203), max 29,776 (n=203) ms; 503 응답 지연 max 30,588 (n=116) ms; 측정용 타임아웃에서 끊긴 GET 5 / 500 (1.0%) | 정상 응답을 끊지 않으려면 관측 max 이상이어야 함. 측정용 타임아웃에서 끊긴 요청이 이후 응답했을지는 미관측 → 그 요청을 기다릴지, 끊고 재시도할지는 '끊은 뒤 재요청 결과'(8.1)와 함께 판단 | 근거 충족 — 11장에서 결정 |
| POST 타임아웃 | HTTP 201 지연 p95 859 (n=36), max 1,895 (n=36) ms | POST 는 재전송 시 중복 레코드 가능성(4장)과 함께 판단 | 근거 충족 — 11장에서 결정 |
| 재시도 간격 | 503 retryAfter 관측값 {30: 72} (n=72); retryAfter 없는 503 44건; Retry-After 헤더 있는 503 0 / 116 (0.0%); 503 이외 일시적 실패 184건 중 Retry-After 제공 0건 | Retry-After 가 제공되면 그 값을 우선 적용(명세 기술 + 실측 제공 여부, 헤더/바디 중 실제 제공처 확인). Retry-After 가 없는 실패(500·타임아웃)의 간격은 '직후 재요청 결과'(8.1)로 판단 | Retry-After 적용 근거 있음 |
| 재시도 횟수 | 연속 503 최대 3 (연속 구간 n=82); 연속 일시적 실패(5xx+예외) 최대 8 (연속 구간 n=102); 일시적 실패 직후 재요청 결과 {'200': 99, 'EXC:ReadTimeout': 4, '500': 86, '503': 52} | 관측된 연속 실패 최대 길이를 넘는 재시도는 근거 없음. 사용자 대기 허용시간과 함께 판단 | 근거 충족 — 11장에서 결정 |
| 폴링 주기 / 첫 폴링 시점 | POST 즉시 최종 18 / 31 (58.1%); [pending 경유] 서버 처리시간 min 34,043 (n=13), p50 55,313 (n=13), p95 85,401 (n=13, 표본 부족: n≥20 필요) ms; estimatedCompletionSeconds 관찰 0/31; 폴링 중 503 lifecycle 13/31 | 처리시간 min 이전 폴링은 pending 만 받음. GET 1회 비용(2장)과 503 발생률(3·8장)을 함께 고려 | 근거 충족 — 11장에서 결정 |
| 폴링 중단 조건 (최대 대기) | [pending 경유] 서버 처리시간 max 85,401 (n=13) ms, p99 85,401 (n=13, 표본 부족: n≥100 필요) ms; 미완료(timeout 0, incomplete 0) / error 0건 (n=31) | 관측 max 이후 완료 사례는 측정되지 않음 → 최대 대기는 관측 max 와의 관계로 설명 | 근거 충족 — 11장에서 결정 |
| 재시도 중단 조건 (비재시도 코드) | 관측 4xx: {'401': 2, '404': 2, '400': 5}; 503 이외 5xx: {'500': 177} | 4xx 는 재요청해도 결과가 같을 가능성 → errors 측정에서 실제 코드 확인 후 판단 | 근거 있음 (errors 측정) |
| 일괄 실행 동시성 상한 | 동시성 1: 503 11 / 40 (27.5%), 5xx/예외 21 / 40 (52.5%); 동시성 5: 503 3 / 20 (15.0%), 5xx/예외 10 / 20 (50.0%); 동시성 10: 503 3 / 20 (15.0%), 5xx/예외 15 / 20 (75.0%) (기준선: 순차 GET 일시적 실패 244 / 413 (59.1%)) | 503/5xx 는 동시성 1 에서도 발생한다. 동시성을 올렸을 때 기준선보다 실패율·지연이 늘어나는 단계가 제한 근거. 측정한 최대 단계까지만 말할 수 있음 | 근거 있음 |

## 10. 측정 한계

- 단일 클라이언트·단일 네트워크 위치·측정 기간 내의 결과이며, 서버 측 부하나 다른 지원자 트래픽은 알 수 없다.
- 지연은 클라이언트 wall-clock 이므로 DNS/TLS/네트워크 변동이 포함된다 (세션 재사용으로 연결 수립은 대부분 제외).
- latency 테스트는 완료된 checkId 1건을 반복 조회한다. pending 중 조회 지연은 2.3의 lifecycle 폴링 표본으로만 확인된다.
- 클라이언트 관측 처리시간은 폴링 간격과 503 대기만큼 과대 측정된다 → 서버 시각 기반 값을 1차 근거로 사용.
- lifecycle 은 합성 employeeId(`LC-...`)와 고정 이름/생년월일로 측정했다. 이름·생년월일에 따라 결과(clear/flagged, 처리시간)가 달라지는지는 측정하지 않았다.
- 동시성 테스트는 외부 API 보호를 위해 낮은 단계만 측정했다. 측정 최대 단계를 넘는 동시성의 거동은 알 수 없다.
- 최종 상태를 관측하면 폴링을 멈추므로, 최종 상태가 이후 바뀌는지는 측정 범위 밖이다.
- 명세에 없는 필드(processingMs, tenant, tenantEmployee 등)는 필드 이름만 기록했고 값·의미는 확정하지 않았다.
- 응답 스키마에 `required` 가 없으므로, 응답에 없던 명세 필드는 '이번 응답에서 관찰되지 않음'으로만 기록했다(명세 위반으로 단정하지 않음).
- **p99 의 한계**: nearest-rank 로 n=100 일 때 p99 는 99번째 값 하나이며, 95% 신뢰구간 상한은 관측 최대값 밖이다(2.1). 즉 n=100 에서도 p99 는 크게 불확실하며, 상위 1~2건의 이상치에 따라 값이 크게 달라진다.
- 측정용 타임아웃(기본 30s)에서 끊은 요청은 그 뒤의 응답 여부를 모른다(우측 절단). 지연 통계는 응답을 받은 요청만으로 계산했다.
- 중단 후 `resume` 으로 확인한 건은 서버 시각 기반 처리시간만 유효하고 클라이언트 관측 처리시간은 없다. 최종 상태에 도달하지 못한 건은 처리시간 통계에서 제외했다.
- 수동 관찰(부록 A)은 요청별 응답시간·요청 시각·요청 횟수가 기록되지 않았으므로 어떤 통계에도 포함하지 않았다.

## 부록 A. 수동 관찰 (자동 측정 통계와 분리)

> `data/manual_observations.csv`. 지원자가 직접 호출해 확인한 사실만 적었다. 요청별 응답시간·요청 시각·관찰 사이의 요청 횟수는 기록되지 않았으므로 비워 두며, 1~9장의 어떤 통계에도 포함하지 않는다. 관찰 건수가 1~3건이므로 API 의 일반적 특성으로 단정하지 않는다.

| ID | 관찰 순서 | 요청 | HTTP | apiStatus | retryAfter(body) | createdAt | completedAt | processingMs | 명세에 없는 필드(이름) | 관찰되지 않은 명세 필드 | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M-1 | 1 | POST /background-checks | 201 | pending |  | 2026-09-22T04:46:16.792Z |  |  |  | estimatedCompletionSeconds | checkId 발급 확인(값은 기록하지 않음). estimatedCompletionSeconds 는 관찰되지 않음. 그 밖의 필드 구성은 기록하지 않음 |
| M-2 | 2 | GET /background-checks/{checkId} | 503 |  | 30 |  |  |  |  |  | 위 checkId GET 조회 중 503. 응답 body 에서 retryAfter=30 확인. Retry-After 헤더 확인 여부는 기록하지 않음. M-1 과 M-2 사이 요청 횟수는 기록하지 않음 |
| M-3 | 3 | GET /background-checks/{checkId} | 200 | clear |  | 2026-09-22T04:46:16.792Z | 2026-09-22T04:46:54.720Z | 37928 | processingMs, tenant, tenantEmployee |  | 동일 checkId 재요청 → 200 clear. processingMs(37928)와 completedAt−createdAt(37.928s) 일치. 명세에 없는 필드 processingMs, tenant, tenantEmployee 존재(값은 기록하지 않음). M-2 이후 대기 시간·요청 횟수는 기록하지 않음 |

- 관찰 순서는 기록된 순서일 뿐이며, 그 사이에 몇 번 요청했는지는 기록되지 않았다.

<!-- MANUAL:START -->
## 11. 최종 결정 (지원자 직접 기입 — `report` 재생성 시 이 블록은 보존됨)

> 이 값들은 측정에서 관찰한 범위와 사용자 대기 시간을 함께 고려한 운영 정책이다. 측정으로 최적값이 증명된 것은 아니다. 재시도·폴링은 서버에서 비동기로 처리하고, 화면 요청 하나가 완료될 때까지 계속 기다리게 하지 않는다.

| 항목 | 결정값 | 근거 (섹션·수치·n) |
|---|---|---|
| GET 타임아웃 | 요청 1회당 35초. 단, 전체 폴링 마감까지 남은 시간이 35초보다 짧으면 남은 시간까지만 기다린다. | 2.1의 성공 GET 최대 29,776ms(n=203), 8.0의 약 30초에 오는 503 최대 30,588ms(n=44)를 모두 관찰할 수 있도록 약 4초의 여유를 뒀다. 기존 30초 설정에서는 GET 시간 초과 5건이 있었다(9장, n=500). 35초가 최적값이라는 뜻은 아니다. |
| POST 타임아웃 | 요청 1회당 5초. POST를 보내기 전에 우리 DB에 '조회 요청 중'을 먼저 기록한다. POST가 실패·시간 초과되면 자동으로 다시 보내지 않는다. POST 직전에 해당 직원의 조회 이력 목록을 받아 두고, 시간 초과 후 목록을 다시 조회해 **요청 전 목록에 없던 checkId가 정확히 1개일 때만** 연결한다. 요청 전 목록 조회가 실패했거나(이때도 POST는 보낸다), 요청 후 목록 조회가 실패했거나, 새 checkId가 0개 또는 여러 개면 추측하지 않고 '확인 필요' 상태로 둔다. 관리자가 다시 확인할지 '생성 안 됨'으로 종료할지 판단한다. | 성공 POST 최대 1,895ms(n=36, 2.4장)이므로 약 3초의 여유를 뒀다. 같은 employeeId로 POST 5회 실행 시 서로 다른 checkId 5개가 만들어졌고 5개 모두 이력 목록에 나타났다(4장, n=5). 시간 초과 후 무조건 재전송하면 중복 조회가 생긴다. 다만 이력 목록 API는 측정에서 7회 중 1회만 200이었으므로(n=7, 표본 적음) 즉시 확인에 의존할 수 없다. 요청 전후 목록 비교 자체는 4장에서 요청 전 목록 조회가 실패해 측정하지 못했다(TBD). 요청 전 목록 조회가 실패해도 POST를 막지 않는 이유는, 정상 입력 POST 36건이 모두 201이라 시간 초과가 관측되지 않은 반면(2.4장, 오류 시험용 400 4건 제외) 목록 조회는 대부분 실패했기 때문이다. |
| 재시도 횟수 | GET의 일시적 실패(500·503·시간 초과)가 **연속** 8회 나오면 중단한다. 200(pending 포함)을 받으면 연속 횟수를 0으로 되돌리며, pending 응답은 재시도로 세지 않는다. POST는 자동 재시도 0회. | 일시적 실패의 연속 길이는 최대 8회였다(8.1장, 연속 구간 n=102). 실패 직후 같은 checkId 재조회가 200으로 돌아온 사례는 99/241건이다(8.1장). 첫 GET 35초·20초 간격이면 180초 안에 보낼 수 있는 GET은 최대 8회(35·55·…·175초)이고 대기·시간 초과가 끼면 더 적으므로, 실제로는 180초 마감이 먼저 적용되고 연속 8회 상한은 안전장치 역할이다. 8회가 성공을 보장하지는 않는다. |
| 재시도 간격 | 503에 Retry-After 헤더가 있으면 그 값을 우선 사용한다. 헤더가 없고 body에 retryAfter가 있으면 body 값을 사용한다. 둘 다 없거나 500·GET 시간 초과이면 다음 20초 폴링 시점에 재조회한다. | 자동 측정 503 중 헤더 제공은 0/116건, body의 retryAfter=30은 72/116건이었다(8장). 503 이외 일시적 실패의 Retry-After 제공은 0/184건이었다(8.1장). 실패 직후 다음 요청의 200 비율은 500 뒤(0.2~2초 후) 50/140, retryAfter=30 대기 후 25/61, 약 30초형 503 뒤 21/35로, 오래 기다린다고 성공률이 뚜렷하게 높아지지 않았다(간격·테스트 조건이 섞여 있어 엄밀한 비교는 아니다). 따라서 20초는 회복을 기다리는 시간이 아니라 외부 API 요청 수를 줄이기 위한 간격이며, 실측으로 최적값을 확정한 것은 아니다. |
| 폴링 주기 (첫 폴링 시점 포함) | POST가 즉시 clear/flagged이면 pending 폴링은 하지 않는다. 대신 POST 응답에 없는 상세 항목(criminalRecord 등)을 받기 위해 GET을 즉시 1회 보내고, 상세가 포함된 성공 응답 1회를 받으면 끝낸다. 이 GET이 500·503·시간 초과로 실패하면 폴링이 아니라 재시도로 보고 아래 재시도 간격·횟수·중단 조건을 적용한다. pending이면 POST 후 35초에 첫 GET을 보내고, 이후 기본 20초 간격으로 GET한다. 503의 Retry-After가 20초보다 길면 그 대기시간을 우선한다. | POST 즉시 최종 상태는 18/31건이었다(5장). pending을 거친 13건의 서버 처리시간은 최소 34,043ms, p50 55,313ms, 최대 85,401ms였다(5장, n=13). 첫 GET을 약 35초에 두면 관찰된 최소 처리시간 전에 반복해서 묻는 요청을 줄일 수 있다. 20초 간격은 p50 부근에서 다음 결과를 확인할 수 있도록 고른 값이며, 최적 간격을 실측으로 확정한 것은 아니다. pending 응답에 이미 들어 있는 processingMs가 최종 처리시간과 24/24 일치했지만(5장) 명세에 없는 필드라 폴링 시점 계산에 쓰지 않고, 명세의 estimatedCompletionSeconds는 0/31 관찰되어 사용할 수 없다(7.3장). |
| 폴링 중단 조건 (최대 대기) | POST 시점부터 최대 180초까지 확인한다. 상세 항목이 포함된 clear/flagged 응답을 받으면 즉시 종료한다(상세 GET과 pending 폴링 모두). 180초가 지나도 최종 상태를 확인하지 못하면 '결과 미확인'으로 기록하고 checkId를 보존하여 관리자가 나중에 다시 조회할 수 있게 한다. | pending 경유 서버 처리시간 최대 85,401ms(n=13, 5장)이므로, 이 정책(35초 후 시작·20초 간격)에서는 가장 늦게 끝난 건도 95초 폴링에서 확인할 수 있고 실패·대기가 몇 번 겹쳐도 180초 안에 들어온다. 참고로 측정 도구 조건(2초 간격 + 30초 대기)에서 클라이언트가 최종 상태를 처음 본 시간은 최대 149,332ms(n=13)였다. 180초 이후에도 완료되지 않는 경우가 없다는 뜻은 아니며(n=13), 그 경우 checkId로 재조회한다. |
| 재시도 중단 조건 | 상세 항목이 포함된 clear/flagged 수신, 전체 180초 경과, 또는 GET 일시적 실패 연속 8회 시 중단한다. 400·401·404는 같은 요청을 자동 반복하지 않고 입력·인증·checkId를 확인한다. 500·503·GET 시간 초과는 위 한도 안에서 재조회한다. 중단해도 500·503을 배경조회 결과인 flagged로 바꾸지 않는다. | 400 5건, 401 2건, 404 2건과 500 177건, 503 116건이 관찰됐다(3장). 일시적 실패 뒤 200으로 돌아온 사례는 99/241건이다(8.1장). 4xx의 정확한 원인은 요청별로 확인해야 하며, 특히 잘못된 키와 없는 checkId를 함께 사용한 시험의 404만으로 인증 동작을 단정하지 않는다(7.5장). |
<!-- MANUAL:END -->
