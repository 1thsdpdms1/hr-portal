-- 개발용 MySQL 준비 (한 번만 실행). DESIGN.md 부록 A.3
-- MySQL Workbench에서 root로 접속해 실행한다.
-- 테이블은 여기서 만들지 않는다. 앱이 시작할 때 Flyway가 만든다.
--
-- 실행 전에 아래 '<개발용 비밀번호>'를 직접 정한 비밀번호로 바꾼다.
-- 바꾼 파일은 저장소에 올리지 않는다(비밀번호가 들어가므로). 복사해서 Workbench에만 붙여 넣는 것을 권한다.

CREATE DATABASE IF NOT EXISTS hr_portal      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS hr_portal_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- root 비밀번호를 앱 설정에 넣지 않기 위해, 두 DB만 다룰 수 있는 앱 전용 사용자를 만든다.
CREATE USER IF NOT EXISTS 'hr_app'@'localhost' IDENTIFIED BY '<개발용 비밀번호>';
GRANT ALL PRIVILEGES ON hr_portal.*      TO 'hr_app'@'localhost';
GRANT ALL PRIVILEGES ON hr_portal_test.* TO 'hr_app'@'localhost';

-- 확인: CHECK 제약은 8.0.16 이상에서만 실제로 검사된다(DESIGN.md 2.3).
SELECT VERSION();
