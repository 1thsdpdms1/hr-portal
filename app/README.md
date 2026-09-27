# hr-portal (사내 직원 관리 시스템)

Java 17 · Spring Boot 3.5.16 · Spring Security · MySQL 8.0 · Gradle Wrapper. 설계는 [../DESIGN.md](../DESIGN.md)를 따른다.

## 처음 한 번
1. MySQL Workbench에서 root로 접속해 [db/dev-setup.sql](db/dev-setup.sql)을 실행한다. 비밀번호 자리(`<개발용 비밀번호>`)를 먼저 바꾼다.
2. IntelliJ IDEA에서 **File → Open → `app/build.gradle` → Open as Project**를 누른다.
3. **Project Structure → SDK**와 **Settings → Gradle → Gradle JVM**을 모두 JDK 17로 맞춘다.

## 실행
`HrPortalApplication`의 ▶를 누른 뒤 **Edit Configurations → Environment variables**에 아래 값을 넣는다. 실행 구성의 "Store as project file"은 켜지 않는다(비밀값이 저장소에 들어갈 수 있다).

```
DB_URL=jdbc:mysql://localhost:3306/hr_portal?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8;DB_USERNAME=hr_app;DB_PASSWORD=<개발용 비밀번호>;SEED_ADMIN_PASSWORD=<관리자 비밀번호>;SEED_EMPLOYEE_PASSWORD=<직원 비밀번호>;CANDIDATE_KEY=<안내 메일의 키>
```

`CANDIDATE_KEY`가 없으면 앱은 뜨지만 배경 조회 요청이 "요청 대기"에서 멈춘다(외부 호출을 하지 않는다). 시작 로그와 조회 상세 화면이 그 사실을 알려 준다.

`SEED_*_PASSWORD`를 넣으면 앱이 시작할 때 계정을 만든다. 관리자 `admin`, 직원 `emp001`(EMP-001)이다. 이미 있는 계정은 건드리지 않으므로 나중에 값을 바꿔도 비밀번호가 되돌아가지 않는다. 환경변수를 넣지 않으면 계정을 만들지 않고 로그에 경고만 남긴다.

실행한 뒤 `http://localhost:8080/login`이 열리면 된다. 8080 포트를 다른 프로그램이 쓰고 있으면 환경변수에 `SERVER_PORT=8081`을 더하고 `http://localhost:8081/login`으로 연다.

로그인하면 관리자는 직원 목록(`/admin/employees`), 직원은 내 정보(`/me`)로 간다. 서로의 화면에 주소로 직접 들어가면 403이 된다.

앱을 실행하면 Flyway가 `hr_portal`에 테이블을 만들고 과제 직원 10명을 넣는다. 이미 적용했다면 다시 실행해도 아무 일도 일어나지 않는다.

## 화면

| 주소 | 누가 | 하는 일 |
|---|---|---|
| `/login` | 누구나 | 로그인. 실패 문구는 원인과 상관없이 같다(계정 존재 여부를 알려 주지 않는다). |
| `/me` | 직원 | 내 정보 조회와 **연락처·이메일 수정**. 성명·생년월일은 읽기 전용이다(배경 조회 입력값이라 관리자만 고친다). |
| `/admin/employees` | 관리자 | 직원 목록(사번·성명·생년월일·재직 상태). 사번을 누르면 상세로 간다. |
| `/admin/employees/new` | 관리자 | 직원 생성. 사번은 서버가 발급하고(현재 최대 + 1), 로그인 계정을 같은 트랜잭션에서 만든다. |
| `/admin/employees/{사번}` | 관리자 | 상세, 신원·연락 정보 수정, 퇴사 처리, 변경 이력, 배경 조회 요청. |
| `/admin/employees/{사번}/background-checks` | 관리자 | 배경 조회 이력(우리 DB 기록). |
| `/admin/background-checks/{id}` | 관리자 | 배경 조회 상세. 민감 항목(범죄 기록·신용 등급 등)은 **이 화면에서만** 보인다. |

모든 변경(본인 수정, 관리자 수정, 퇴사 처리)은 `employee_change_log`에 바뀐 필드만 한 줄씩 남는다.
퇴사 처리는 직원 상태와 로그인 계정 비활성화를 한 트랜잭션에서 처리하므로, 그 직원은 **다음 요청부터** 로그인 화면으로 밀린다.

## 배경 조회 동작

화면에서 "배경 조회 요청"을 누르면 `QUEUED` 행만 만들고 바로 응답한다. **외부 API 호출은 화면 요청이 아니라 `@Scheduled` 워커가 한다.**
실측에서 정상 GET도 최대 29.7초였고 순차 GET의 59.1%가 500·503·타임아웃이었기 때문이다(`../measurement/MEASUREMENTS.md` 1·2-4).

타이밍 값은 모두 MEASUREMENTS.md 7~12장의 결정값이며 `application.yml`의 `bgcheck` 한 곳에 있다
(GET 35초 · POST 5초 · 첫 폴링 35초 · 이후 20초 · 마감 180초 · 일시적 실패 연속 8회).

- 인증키는 환경변수 `CANDIDATE_KEY`로만 받는다. 없으면 워커가 호출을 건너뛰고 경고만 남긴다.
- POST는 어떤 경우에도 자동으로 다시 보내지 않는다. 결과가 불확실하면 요청 전후 목록의 차이가 **정확히 1개일 때만** checkId를 연결하고, 아니면 "확인 필요"로 남긴다.
- 500·503·타임아웃은 결과 컬럼을 건드리지 않는다. "조회 실패"와 "이상 있음"을 구분하기 위해서다.
- 화면은 배지를 둘로 나눠 보여 준다: **결과**(대기 중/이상 없음/추가 검토 필요)와 **연동 상태**(요청 대기/조회 중/정상/확인 필요/결과 미확인).

로컬에서 워커를 끄고 싶으면 환경변수 `BGCHECK_WORKER_ENABLED=false`를 준다.

## 테스트
```
gradlew.bat test
```
`LoginPageTest`는 DB 없이 돌아간다. 나머지는 `hr_portal_test`에 연결한다(`test-db.local.properties` 필요).
