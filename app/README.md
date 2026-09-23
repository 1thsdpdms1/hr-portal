# hr-portal (사내 직원 관리 시스템)

Java 17 · Spring Boot 3.5.16 · Spring Security · MySQL 8.0 · Gradle Wrapper. 설계는 [../DESIGN.md](../DESIGN.md)를 따른다.

## 처음 한 번
1. MySQL Workbench에서 root로 접속해 [db/dev-setup.sql](db/dev-setup.sql)을 실행한다. 비밀번호 자리(`<개발용 비밀번호>`)를 먼저 바꾼다.
2. IntelliJ IDEA에서 **File → Open → `app/build.gradle` → Open as Project**를 누른다.
3. **Project Structure → SDK**와 **Settings → Gradle → Gradle JVM**을 모두 JDK 17로 맞춘다.

## 실행
`HrPortalApplication`의 ▶를 누른 뒤 **Edit Configurations → Environment variables**에 아래 값을 넣는다. 실행 구성의 "Store as project file"은 켜지 않는다(비밀값이 저장소에 들어갈 수 있다).

```
DB_URL=jdbc:mysql://localhost:3306/hr_portal?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8;DB_USERNAME=hr_app;DB_PASSWORD=<개발용 비밀번호>
```

실행한 뒤 `http://localhost:8080/login`이 열리면 된다. 8080 포트를 다른 프로그램이 쓰고 있으면 환경변수에 `SERVER_PORT=8081`을 더하고 `http://localhost:8081/login`으로 연다. 아직 계정이 없어서(3단계에서 추가) 로그인을 시도하면 항상 실패한다.

앱을 실행하면 Flyway가 `hr_portal`에 테이블을 만들고 과제 직원 10명을 넣는다. 이미 적용했다면 다시 실행해도 아무 일도 일어나지 않는다.

## 테스트
```
gradlew.bat test
```
1단계 테스트(`LoginPageTest`)는 DB 없이 돌아간다.
