# hr-portal (사내 직원 관리 시스템)

Java 17 · Spring Boot 3.5.16 · Spring Security · MySQL 8.0 · Gradle Wrapper. 설계는 [../DESIGN.md](../DESIGN.md)를 따른다.

## 처음 한 번
1. MySQL Workbench에서 root로 접속해 [db/dev-setup.sql](db/dev-setup.sql)을 실행한다. 비밀번호 자리(`<개발용 비밀번호>`)를 먼저 바꾼다.
2. IntelliJ IDEA에서 **File → Open → `app/build.gradle` → Open as Project**를 누른다.
3. **Project Structure → SDK**와 **Settings → Gradle → Gradle JVM**을 모두 JDK 17로 맞춘다.

## 실행
`HrPortalApplication`의 ▶를 누른 뒤 **Edit Configurations → Environment variables**에 아래 값을 넣는다. 실행 구성의 "Store as project file"은 켜지 않는다(비밀값이 저장소에 들어갈 수 있다).

```
DB_URL=jdbc:mysql://localhost:3306/hr_portal?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8;DB_USERNAME=hr_app;DB_PASSWORD=<개발용 비밀번호>;SEED_ADMIN_PASSWORD=<관리자 비밀번호>;SEED_EMPLOYEE_PASSWORD=<직원 비밀번호>
```

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
| `/admin/employees/{사번}` | 관리자 | 상세, 신원·연락 정보 수정, 퇴사 처리, 변경 이력. Background Check 영역은 6단계에서 붙인다. |

모든 변경(본인 수정, 관리자 수정, 퇴사 처리)은 `employee_change_log`에 바뀐 필드만 한 줄씩 남는다.
퇴사 처리는 직원 상태와 로그인 계정 비활성화를 한 트랜잭션에서 처리하므로, 그 직원은 **다음 요청부터** 로그인 화면으로 밀린다.

## 테스트
```
gradlew.bat test
```
`LoginPageTest`는 DB 없이 돌아간다. 나머지는 `hr_portal_test`에 연결한다(`test-db.local.properties` 필요).
