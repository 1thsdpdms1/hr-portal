# Background Check API 실측 도구 — 설명서

사내 직원 관리 시스템의 관리자 기능 중 **Background Check 연동**은 외부 API(`swagger.yaml`)에 의존한다.
과제 요구사항대로 타임아웃·재시도·폴링 값은 "일반적으로 권장되는 값"이 아니라 **실측값을 근거로** 정해야 하므로,
명세를 믿고 구현하기 전에 실제 거동을 측정하는 도구를 먼저 만들었다.

```
measurement/
├─ measure_background_api.py   # 측정 + 보고서 생성 (단일 파일)
├─ requirements.txt            # requests, PyYAML
├─ README_MEASUREMENT.md       # 이 문서
├─ data/
│  ├─ measurements.csv         # 모든 HTTP 요청 1건 = 1행 (원시값, 누적)
│  ├─ lifecycles.csv           # Background Check 1건의 POST→최종상태 과정 = 1행 (append-only, checkId 별 최신 행 사용)
│  ├─ manual_observations.csv  # 지원자가 직접 확인한 수동 관찰 (자동 측정 통계와 분리)
│  └─ runs.csv                 # 실행 이력(명령, 파라미터, 동시성 단계 판단)
└─ MEASUREMENTS.md             # CSV 로부터 자동 생성되는 제출물 (7~12장만 지원자가 직접 작성)
```

---

## 0. 실측 결과 요약

- 측정 기간: 2026-09-22 12:37:50 ~ 14:56:42 (UTC), 단일 클라이언트(지원자 PC)
- 원시 데이터: HTTP 요청 **549건**(`measurements.csv`), Background Check **31건**(`lifecycles.csv`, checkId 기준)
- 아래 수치는 모두 `MEASUREMENTS.md` 에서 그대로 옮긴 것이다. 상세 표와 n 은 해당 장을 본다.

| 주제 | 관측 결과 (n) | 보고서 |
|---|---|---|
| HTTP 상태 분포 | 200 204, 201 36, **500 177 (32.2%)**, **503 116 (21.1%)**, 400 5, 401 2, 404 2, 30s 측정 타임아웃 7 (n=549) | 3장 |
| 순차 GET 일시적 실패 | **244/413 (59.1%)** — 500 142, 503 97, 타임아웃 5 | 2-4 |
| GET 정상(200) 지연 | p50 **458ms**, p95 **26,636ms**, p99 **29,414ms**, max **29,776ms** (n=203). 1초 미만 다수 + 수~수십 초의 긴 꼬리 | 1-1 |
| p99 불확실성 | p99 95% 신뢰구간 [28,499ms, 관측 범위 밖] (n=203) | 1-1 |
| 503 두 종류 | ① 즉시(20~513ms), body `retryAfter=30`, 72건 ② **약 30초(30,008~30,588ms)** 후, `{"message":"Service Unavailable"}` 만, retryAfter 없음, 44건 | 2-3 |
| Retry-After | **헤더 0/116**, body `retryAfter` 72/116 (값은 모두 30). 500 등 503 이외 실패는 0/184 | 8장 |
| 재시도 근거 | 일시적 실패 직후 재요청 200 **99/241**, 연속 일시적 실패 최대 **8회**(구간 n=102), 연속 503 최대 3회(n=82) | 2-4 |
| 처리시간 | POST 응답이 **즉시 clear 18/31**(서버 처리시간 0ms). pending 경유 13건: min **34,043** / p50 **55,313** / max **85,401ms** | 5장 |
| 최종 상태 | 31건 모두 `clear`, `flagged` 0건 | 5장 |
| processingMs | (completedAt − createdAt) 과 31/31 일치. pending 응답에도 이미 존재하며 최종값과 24/24 일치 (명세에 없는 필드, 의미 미확정) | 5장 |
| estimatedCompletionSeconds | 명세 예시 20초지만 **POST 201 응답 36/36 에서 관찰되지 않음** | 6-6 |
| 명세에 없는 필드 | GET 200 응답에 `processingMs`, `tenant`, `tenantEmployee` (203/203) | 6-5 |
| 동일 employeeId 반복 POST | 5회 모두 201, **서로 다른 checkId 5개**, 5개 모두 이력 목록에 나타남(목록 totalCount 6) | 4장 |
| 동시성 1→5→10 (GET) | 503 비율 30% → 15% → 15% (각 n=20), 5xx/예외 65% → 50% → 75%. 동시성 증가에 따른 503 증가는 관측되지 않음 | 6장 |
| 오류 케이스 (n=2씩) | dateOfBirth 누락·빈 값 → 400, 키 없음 → 401, employeeId 쿼리 누락 → 400(1회, 다른 1회는 500), 잘못된 키+없는 checkId → 404, 없는 checkId(유효 키) → 500·503만 관찰 | 6-8 |
| 시계 차이 | 서버 createdAt 이 클라이언트 POST 시각보다 6,515~7,564ms 이름 (n=36) → 처리시간은 서버 시각끼리만 계산 | 5장 |
| 이력 목록 API | `GET ?employeeId=` 7회 중 200 1회 (500·503·30s 타임아웃) | 2-2 |

**최종 결정(7~12장, 지원자 작성)**: GET 타임아웃 35초 · POST 타임아웃 5초(자동 재전송 없음) · GET 일시적 실패 연속 8회 상한 ·
Retry-After(헤더 → body) 우선, 없으면 20초 폴링 시점에 재조회 · pending 이면 POST 후 35초에 첫 GET, 이후 20초 간격 · 최대 180초 후 '결과 미확인'.

---

## 1. 실행 방법

### 준비 (Windows PowerShell)

```powershell
# Python 3.10+ 설치 후 (Windows 스토어의 python 바로가기가 아니라 실제 설치본인지 `python --version` 으로 확인)
cd measurement
python -m pip install -r requirements.txt

# 키는 환경변수로만 전달 (코드/CSV/문서/Git 어디에도 기록하지 않음)
$env:CANDIDATE_KEY = "<안내 메일의 키>"
```

### 실제로 실행한 순서 (`runs.csv` 기준)

```powershell
python measure_background_api.py report                                  # 키 없이 스모크 테스트
python measure_background_api.py lifecycle --samples 1                   # 연결 확인
python measure_background_api.py lifecycle --samples 10                  # 처리시간 1차
python measure_background_api.py resume --backfill                       # POST 즉시 clear 건의 서버 시각 보충
python measure_background_api.py latency --samples 20 --timeout 90       # 30초 '무응답'의 정체 확인
python measure_background_api.py latency --samples 100 --timeout 90 --max-requests 300
python measure_background_api.py duplicate --employee EMP-001 --count 5
python measure_background_api.py errors
python measure_background_api.py concurrency --levels 1,5,10 --requests-per-level 20 --timeout 90
python measure_background_api.py lifecycle --samples 20 --timeout 90     # pending 경유 표본 확보
python measure_background_api.py resume --backfill
python measure_background_api.py concurrency --levels 1,5,10 --requests-per-level 20 --timeout 90   # 규칙 수정 후 재실행
python measure_background_api.py list --employee EMP-001 --timeout 90    # duplicate 목록 재확인
python measure_background_api.py errors --timeout 90                     # 오류 케이스 2회차
```

- `--timeout 90`: 기본 30초로는 약 30초에 오는 503 을 받기 직전에 끊어 버린다. 90초로 늘리자 30초 '무응답'이 모두 30.0~30.6초에 오는 503 으로 확인됐다.
- `--max-requests 300`: 순차 GET 의 약 60% 가 실패해서 기본 상한(표본 × 2)으로는 정상 응답 100개를 모을 수 없었다.
- 한 번에 한 명령만 실행한다. 동시에 돌리면 서로 부하를 줘서 측정값이 오염된다.

모든 측정 명령은 요청 1건마다 CSV 에 즉시 append 하므로, 중간에 Ctrl+C 로 멈춰도 그때까지의 데이터는 남고 보고서도 갱신된다.
lifecycle 을 중간에 멈추면 폴링 중이던 checkId 는 `outcome=incomplete`(Ctrl+C) 로 남거나, 강제 종료라면 POST 행만 남는다.
두 경우 모두 `resume` 이 저장된 checkId 로 조회를 이어가며, **최종 상태에 도달하지 못한 건은 미완료로 표시되고 처리시간 통계에서 제외**된다.

### 보조 명령

```powershell
python measure_background_api.py resume                     # 최종 상태 미도달 checkId 의 pending 조회 재개
python measure_background_api.py resume --backfill          # POST 즉시 최종이라 completedAt 이 비어 있는 건을 GET 1회로 보충
python measure_background_api.py list --employee EMP-001    # 직원별 이력 목록만 재조회 (레코드 생성 없음)
python measure_background_api.py seed-manual                # manual_observations.csv 생성/정리
# (선택, 실행하지 않음) POST 동시성 — 레코드를 실제로 생성하므로 별도 옵션 + 총 요청 수 상한(기본 15, 절대 30, 단계당 최대 5)
python measure_background_api.py concurrency --method post --levels 1,3 --requests-per-level 5
```

### 주요 옵션 (모두 "측정 도구 파라미터"이며 정책값이 아님)

| 옵션 | 기본값 | 의미 |
|---|---|---|
| `--timeout` | 30 | 측정용 HTTP 타임아웃. 넘으면 `EXC:ReadTimeout` 으로 기록 (이 API 에는 90 권장, 위 설명) |
| `--poll-interval` | 2 | lifecycle 폴링 간격 = 클라이언트 관측 처리시간의 해상도 |
| `--max-wait` | 300 | lifecycle 1건 최대 대기. 넘으면 `outcome=timeout` (값을 만들어내지 않음) |
| `--max-retry-wait` | 120 | 503 Retry-After 대기 상한 (측정 중 무한 대기 방지) |
| `--no-wait-on-503` | off | 503 을 받아도 Retry-After 만큼 쉬지 않음 |
| `--max-requests` | 표본×2 | latency 최대 요청 수 |
| `--pause` | 5 | 동시성 단계 사이 기본 대기. Retry-After 를 받으면 그 값 이상 대기 |
| `--abort-error-ratio` | 0.9 | 동시성 5xx/예외 비율 절대 상한 |
| `--abort-margin` | 0.3 | 첫 단계 대비 5xx/예외 비율이 이만큼 늘면 동시성 중단 |
| `--probe-attempts` | 3 | 503 비율 증가 시 대기 후 회복 확인 GET 최대 횟수 |
| `--method` | get | 동시성 대상. `post` 는 별도 지정 + `--post-limit` 상한 |
| `--list-attempts` | 5 | duplicate/list 의 목록 조회 최대 시도 횟수 |

---

## 2. 설계 원칙

1. **명세가 아니라 CSV 가 유일한 근거.** `MEASUREMENTS.md` 는 `data/*.csv` 만 읽어 생성한다. 측정하지 않은 칸은 `TBD`.
2. **모든 통계에 n.** p50/p95/p99/max 셀마다 `(n=…)` 을 붙이고, p95 는 n<20, p99 는 n<100 이면 `표본 부족` 표시.
3. **백분위수는 nearest-rank.** 정렬 후 `x[ceil(q·n)]`. 보간하지 않으므로 보고된 값은 항상 실제로 관측된 응답시간이다.
   보고서 2.1 에는 순위(예: n=203 → p99 = 201번째)와 **분포 가정 없는 95% 신뢰구간**(순서통계량 + 이항분포)을 함께 적는다.
   n=100 에서도 p99 신뢰구간의 상한은 관측 최대값 밖이다(n=203 인 실측도 [28,499ms, 상한 없음]). 그래서 p99 는 "관측된 꼬리"로만 쓴다.
4. **HTTP 상태와 업무 상태를 분리.** `httpStatus`(200/503…)와 `apiStatus`(pending/clear/flagged)는 다른 컬럼.
   "503 을 받았다"와 "아직 pending 이다"는 다른 상황이고, 연동 코드에서도 다른 분기로 처리해야 한다.
5. **명세 필드 목록을 하드코딩하지 않음.** `swagger.yaml` 을 파싱해 `paths → responses → $ref` 를 따라가 스키마를 찾고,
   실제 응답 키와 비교한다(**필드 이름만**, 값은 비교·저장하지 않음). 명세에 없는 필드는 **존재만 기록**하고 의미는 확정하지 않는다.
   이 swagger 의 응답 스키마에는 `required` 가 없으므로, 응답에 없던 명세 필드는 **"이번 응답에서 관찰되지 않음"** 으로 기록하고 명세 위반으로 쓰지 않는다.
6. **민감정보를 남기지 않음.** 요청/응답 본문은 저장하지 않는다. `CANDIDATE_KEY` 는 환경변수에서만 읽고,
   이름·생년월일·tenant/tenantEmployee 의 실제 값은 콘솔·CSV·Markdown 어디에도 쓰지 않는다
   (정상 응답에서 수집한 민감값이 서버 에러 메시지에 섞이면 `***` 로 마스킹, `runs.csv` 에서 이름/생년월일 인자 제외, duplicate 도 이름을 출력하지 않음).
7. **외부 API 보호.** 동시성은 오름차순(1→5→10)만 허용, 20 초과는 `--force` 필요.
   - 기본은 **기존(완료된) checkId 에 대한 GET** — 새 레코드를 만들지 않는다.
   - 단계 사이 `--pause` 만큼 대기하고, **Retry-After 를 받으면 그 값만큼 대기**한다.
   - 이 API 는 **동시성 1 에서도 GET 의 절반 이상이 500/503** 이므로, '오류가 났다'가 아니라 **첫 단계(기준선) 대비 늘었는가**로 판단한다.
   - 5xx/예외 비율이 첫 단계보다 0.3 이상 늘거나 0.9 를 넘으면 중단. 503 비율이 이전 단계보다 늘면 대기 후 확인 GET(최대 3회), 모두 실패하면 중단.
   - POST 동시성은 `--method post` 로만, 단계당 최대 5·총 요청 기본 15건(절대 30건) 상한, 합성 employeeId(`CC-…`) 사용.
8. **측정 데이터와 업무 데이터 분리.** lifecycle 은 시드 직원이 아닌 합성 ID `LC-<runId>-001` 을 쓴다.
   (duplicate 만 과제 요구대로 EMP-001 을 사용)
9. **수동 관찰은 별도 파일.** 직접 확인한 1차 결과는 `data/manual_observations.csv` 에만 기록한다. 응답시간이 없어 어떤 통계에도 넣을 수 없으므로 MEASUREMENTS.md 본문에도 싣지 않는다.
   기록되지 않은 요청별 latency·요청 시각·관찰 사이 요청 횟수는 컬럼 자체를 두지 않았다. 1~6장의 통계는 자동 측정만으로 계산한다.
10. **중단돼도 이어서 측정.** lifecycle 은 POST 직후 checkId 가 CSV 에 저장되므로 `resume` 으로 재개한다.
    처리시간은 서버 시각(completedAt − createdAt)이라 재개해도 유효하다.

### 수동 관찰 (manual_observations.csv) — 자동 측정으로 어떻게 확인됐나

| 수동 관찰 (n=1) | 자동 측정 결과 |
|---|---|
| POST 201 pending, `estimatedCompletionSeconds` 관찰되지 않음 | POST 201 36/36 에서 관찰되지 않음. 단, POST 즉시 clear 도 18/31 |
| GET 503, body `retryAfter=30` | 빠른 503 은 72/72 가 body `retryAfter=30`, 헤더는 0/116. 별도로 retryAfter 없는 약 30초 503 도 44건 |
| 200 clear, processingMs 37,928 = completedAt − createdAt | 31/31 일치. pending 경유 처리시간은 34,043~85,401ms (n=13) |
| 명세에 없는 `processingMs`, `tenant`, `tenantEmployee` | GET 200 203/203 에서 존재 |

---

## 3. 각 측정이 왜 필요했고, 어떤 결정으로 이어졌나

| 측정 | 왜 필요했나 | 실측 결과 | 연동 구현에서 이어진 결정 (7~12장) |
|---|---|---|---|
| **GET 지연** (`latency`) | 명세에 응답시간이 없다. 정상 응답이 최대 얼마나 늦게 오는지 알아야 타임아웃을 정한다. | 200 max 29,776ms (n=203), 약 30초형 503 max 30,588ms (n=44) | **GET 타임아웃 35초.** 둘 다 끝까지 관찰할 수 있는 최소 구간 + 여유. 30초면 30초형 503 을 받기 직전에 끊는다. |
| **HTTP 상태 분포** | 오류가 가끔인지 자주인지에 따라 구조가 달라진다. | 순차 GET 59.1% 일시적 실패 (n=413) | 오류가 '예외'가 아니라 **일상**이다 → 화면 요청 안에서 기다리지 않고 **서버 비동기 폴링** + '조회 중' 상태 저장. |
| **503 + Retry-After** | 명세는 헤더로 Retry-After 를 준다고 한다. 실제 제공 위치·값 확인. | 헤더 0/116, body 72/116(=30). 약 30초형 503 과 500 은 간격 정보 없음 | **헤더 → body 순서로 파싱**, 둘 다 없으면 다음 20초 폴링 시점. 헤더만 믿는 구현은 이 API 에서 한 번도 동작하지 않는다. |
| **재시도 후 결과** | 몇 번까지 다시 시도할 근거가 필요하다. | 실패 직후 재요청 200 99/241, 연속 실패 최대 8 (n=102). 오래 기다려도 다음 성공률이 뚜렷하게 오르지 않음 | **연속 8회 상한**(200 받으면 리셋), 실제로는 180초 마감이 먼저 적용. 20초 간격은 회복 대기가 아니라 요청 수 절감 목적. |
| **처리시간** (`lifecycle`) | 명세 `estimatedCompletionSeconds`(예시 20초)를 폴링 근거로 쓸 수 있는지 검증. | 필드 0/36 관찰. 즉시 clear 18/31, pending 경유 34~85초 (n=13) | POST 가 즉시 최종이면 **폴링하지 않음**. pending 이면 **35초 후 첫 GET**(최소 34,043ms 근거), 이후 20초 간격, **180초 후 '결과 미확인'** + checkId 보존. |
| **processingMs 일치** | 처리시간의 기준값을 정해야 한다. | 31/31 일치, pending 에도 24/24 미리 존재 | 기준은 명세에 있는 `createdAt`/`completedAt`. `processingMs` 는 명세에 없어 **의존하지 않음**(폴링 시점 계산에도 쓰지 않음). |
| **반복 POST** (`duplicate`) | 버튼 중복 클릭·타임아웃 후 재전송 시 동작이 명세에 없다. | 5회 → checkId 5개, 모두 이력에 남음 | 서버가 중복을 막지 않는다 → **POST 자동 재전송 금지**, POST 전 DB 에 '조회 요청 중' 기록, 진행 중 건이 있으면 새 POST 차단. |
| **이력 목록 조회** (`list`) | POST 타임아웃 후 생성 여부를 확인할 수단인지 확인. | 7회 중 200 1회 | 즉시 확인에 의존 불가 → 확인도 실패하면 **'확인 필요'** 로 두고 관리자 판단. |
| **동시성 1→5→10** | 여러 직원을 한 번에 조회하는 상황. | 503 비율 30%→15%→15% (각 n=20). 동시성 증가에 따른 503 증가 없음 | 측정 범위(10)에서는 동시성 때문에 막힌다는 근거 없음. 그 이상은 근거 없음으로 둔다. |
| **오류 케이스** (`errors`) | 비재시도 조건과 입력 검증 위치를 정해야 한다. EMP-007 은 생년월일이 없다. | dateOfBirth 누락·빈 값 → 400 (각 2/2) | **EMP-007 은 API 호출 전에 우리 쪽에서 차단**('생년월일 확인 필요'). 400·401·404 는 자동 반복하지 않음. |
| **Swagger 필드 대조** | 없는 필드에 의존하는 코드를 막는다. | 명세 밖 필드 3개 상시 존재, 명세 필드 1개 미관찰 | **관대한 파싱**(unknown 필드 무시). `tenant` 류는 의미를 모르므로 저장·노출하지 않음(민감정보 최소 보관). |

---

## 4. MEASUREMENTS.md 는 어떻게 읽는가

1. 1~8장: 목적·환경 / GET 지연 / 상태코드 / 반복 POST / 처리시간 / 동시성 / 명세 대조 / 503·Retry-After — 모두 CSV 에서 자동 계산.
   현재 모든 항목이 `근거 충족` 또는 `근거 있음` 이다.
3. **7~12장 "결정"** — `<!-- MANUAL:START -->` ~ `<!-- MANUAL:END -->` 블록. 지원자가 직접 결정값과 근거를 쓴다.
   `report` 를 다시 돌려도 이 블록은 보존된다(블록이 여러 개면 기본 템플릿이 아닌 마지막 블록을 보존). 숫자는 자동, 판단은 사람.
4. 수동 관찰은 `data/manual_observations.csv` 에만 있다. 어떤 통계에도 포함하지 않는다.

---

## 5. 면접 예상 질문과 답변 포인트

- **"GET 타임아웃을 왜 35초로 했나요?"** → 정상 응답 최대 29,776ms(n=203)와 약 30초에 오는 503 최대 30,588ms(n=44)를 끝까지 받기 위해서다.
  처음 30초로 측정했을 때 끊긴 요청이 있었는데, 90초로 다시 재 보니 모두 30초 부근에 오는 503 이었다. 30초로 두면 그 503 을 받기 직전에 끊는다.
  35초가 최적이라는 증명은 아니다.
- **"p99 는 어떻게 계산했나요?"** → nearest-rank, `ceil(0.99·n)` 번째 값. n=203 이면 201번째. 신뢰구간 상한이 데이터 밖이라 p99 를 확정값처럼 쓰지 않는다.
- **"재시도 8회의 근거는?"** → 연속 일시적 실패 최대가 8회였다(n=102). 기준을 '연속'으로 둔 이유는 근거가 연속 길이이기 때문이고,
  200(pending 포함)을 받으면 리셋한다. 35초 후 시작·20초 간격이면 180초 안에 GET 은 최대 8회라서 실제로는 180초가 먼저 걸리고, 8회는 안전장치다.
- **"왜 20초 간격인가요?"** → 서버가 알려 준 값이 아니다. 30초를 기다린 뒤의 재요청도 바로 다시 보낸 경우보다 성공률이 뚜렷하게 높지 않았다
  (retryAfter 대기 후 25/61, 500 직후 50/140). 그래서 회복 대기가 아니라 외부 API 요청 수를 줄이기 위한 간격이라고 설명한다.
- **"Retry-After 는 어떻게 읽나요?"** → 명세는 헤더라고 하지만 헤더는 0/116 이었고 body `retryAfter` 가 72/116 이었다. 헤더 → body 순서로 읽는다.
  같은 503 이라도 retryAfter 없이 30초 뒤에 오는 종류(44건)가 있어서, 없을 때의 규칙이 반드시 필요하다.
- **"POST 는 재시도하나요?"** → 하지 않는다. 같은 employeeId 로 5번 보내면 checkId 가 5개 생기고 모두 이력에 남았다(n=5). 재전송은 민감정보 중복 조회를 만든다.
  타임아웃 시 이력 목록으로 확인하지만 목록 API 도 7회 중 1회만 성공해서, 실패하면 '확인 필요'로 두고 관리자가 판단한다.
- **"폴링은 왜 35초 후 시작인가요?"** → 58% 는 POST 에서 바로 clear 라 폴링이 필요 없고, pending 을 거친 건은 최소 34,043ms 가 걸렸다(n=13).
  그 전에 물어봐야 pending 만 받는다. 명세의 `estimatedCompletionSeconds` 는 한 번도 오지 않았고, `processingMs` 는 명세에 없어 쓰지 않았다.
- **"최대 대기 180초는?"** → pending 처리시간 최대 85,401ms 라 이 정책에서는 95초 폴링에서 확인된다. 실패·대기가 겹칠 여유를 둔 값이고,
  180초 후에도 끝나지 않으면 '결과 미확인'으로 두고 checkId 로 나중에 재조회한다. n=13 이라 그 이후 완료 사례가 없다고는 말할 수 없다.
- **"필드가 안 왔는데 왜 명세 위반이라고 안 썼나요?"** → 응답 스키마에 `required` 가 없어서 모든 응답 필드가 선택적이다.
  다만 '폴링 주기에 쓰라'는 필드가 한 번도 오지 않았으므로 연동 코드가 의존하면 안 된다는 근거가 된다.
- **"잘못된 키가 404 였다면서요?"** → 그 시험은 잘못된 키와 존재하지 않는 checkId 를 함께 보냈다. 404 가 어느 쪽 때문인지 구분할 수 없어서 인증 동작을 단정하지 않았다.
- **"동시성 10 까지만 한 이유는?"** → 다른 지원자와 공유하는 외부 API 라 점진적으로만 올렸다. 10 까지 503 증가는 없었고, 그 이상은 측정하지 않았다고 적었다.
- **"측정이 중간에 끊기면요?"** → checkId 는 POST 직후 CSV 에 남는다. `resume` 이 이어서 조회하고, 끝내 최종 상태를 못 본 건은 '미완료'로 처리시간 통계에서 뺀다.
  실제 연동도 같은 구조다(checkId 를 DB 에 먼저 저장 → 서버 재시작 후에도 재개).

---

## 6. 측정 도구를 만들며 실측으로 발견해 고친 지점 (AI_LOG 참고)

| 무엇이 틀렸나 | 어떻게 발견했나 | 어떻게 고쳤나 |
|---|---|---|
| 동시성 안전장치가 'Retry-After 받으면 확인 GET 1회, 실패하면 중단'이라 평소 실패(약 60%)를 과부하로 오판, 1단계에서 멈춤 | 실측 (보고서 6장 '(이전 규칙)' 행) | 첫 단계 실패율을 기준선으로 두고 증가폭으로 판단, 확인 GET 최대 3회 |
| 명세 스키마가 없는 응답(401)의 message 까지 민감값으로 처리해 `***` 로 가려짐 | CSV 확인 | 민감값은 정상(2xx) 응답에서만 수집 |
| POST 가 즉시 clear 면 GET 을 하지 않아 7건의 서버 처리시간이 비어 있음 | 실측 (lifecycles.csv) | 즉시 최종이면 GET 1회로 서버 시각 확보, 기존 건은 `resume --backfill` |
| 재시도 분석이 503 만 세고 더 흔한 500·타임아웃을 빠뜨림 | 실측 (첫 lifecycle 1건) | 5xx + 예외를 '일시적 실패'로 묶은 절 추가 |
| duplicate 의 목록 조회가 503 한 번에 포기해 전/후 비교가 비어 있음 | 모의 서버 테스트 | 목록 조회를 Retry-After 존중하며 재시도, 이후 `list` 명령 추가 |
| 보고서가 '잘못된 키 → 404, 명세와 다름'이라고 단정 | 코드 리딩 (지원자가 결정 장 작성 중 지적) | 없는 checkId 와 함께 보낸 시험이라 원인 구분 불가하다는 주의 문구로 수정 |
| 결정 블록이 두 개일 때 첫 번째(빈 템플릿)만 보존해 작성 내용이 지워질 뻔함 | 코드 리딩 | 편집된 마지막 블록을 보존하도록 수정 |
| 측정 기본 타임아웃 30초가 약 30초에 오는 503 을 '무응답'으로 잘라 기록 | 실측 (`--timeout 90` 재측정) | 이후 측정은 90초로 실행, 보고서 2-3 에 503 유형 분리 |

---

## 7. 측정 한계

- 단일 PC·단일 네트워크·약 2시간 20분의 결과. 서버 측 부하, 다른 지원자 트래픽의 영향은 알 수 없다.
- latency 테스트는 완료된 checkId 1건을 반복 조회했다(서버 캐시 효과 가능성). pending 중 조회 지연은 lifecycle 폴링 표본으로만 확인된다.
- lifecycle 은 고정된 합성 이름/생년월일을 썼다. 31건 모두 clear 였고 **flagged 는 한 번도 관측하지 못했다** — flagged 경로의 처리시간·응답은 모른다.
- pending 경유 처리시간은 n=13 이라 p95/p99 는 표본 부족이다. 85초 이후 완료되는 경우가 없다고 말할 수 없다.
- 동시성은 단계별 n=20, 한 번(규칙 수정 후) 측정했다. 10 초과는 측정하지 않았다.
- 유효한 키로 없는 checkId 를 조회했을 때의 404 는 직접 관찰하지 못했다(2회 모두 500·503). 잘못된 키 단독의 응답도 확인하지 않았다.
- duplicate 는 '전' 목록 조회가 실패해 레코드 증가분(전/후 totalCount 차이)은 계산하지 못했다. 생성된 checkId 5개가 목록에 모두 있음만 확인했다.
- 30초 측정 타임아웃에서 끊긴 7건이 약 30초형 503 과 같은 응답이었는지는 직접 확인하지 않았다(90초 측정에서는 끊긴 건이 0건).
- 수동 관찰은 n=1 이고 응답시간이 기록되지 않아 어떤 통계에도 포함하지 않았다.
