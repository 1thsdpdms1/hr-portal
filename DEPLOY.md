# DEPLOY — AWS EC2 배포 절차

사내 직원 관리 시스템을 EC2 한 대에 올린다. 구성은 다음과 같다.

```
인터넷 → caddy(443, 자동 HTTPS) → app(8080) → mysql(3306, 컨테이너 내부 네트워크만)
```

**왜 이 구성인가**

- 이 앱은 `@Scheduled` 워커가 외부 Background Check API를 호출한다. 요청이 없을 때도 돌아야 하므로 **상시 실행형**이어야 한다(요청 때만 깨어나는 서버리스·휴면형은 맞지 않는다).
- 로그인 세션이 앱 메모리에 있고 워커도 하나여야 하므로 **앱 인스턴스는 1개**로 고정한다.
- RDS를 쓰지 않고 같은 인스턴스에 MySQL 컨테이너를 올린다. 직원 10명 규모에 충분하고, 프리티어가 끝난 계정에서는 RDS가 월 $12 이상으로 비용의 대부분을 차지한다.
- 리전은 **서울(ap-northeast-2)** 이다. 외부 API가 같은 리전에 있어, `measurement/MEASUREMENTS.md` 7~12장의 타이밍 값을 측정한 환경과 지연 특성이 가장 가깝다.

---

## 0. 비용 (이 계정 기준)

이 프로젝트가 쓰는 계정은 **2026-09-26에 만든 새 요금 체계 계정**이다. 콘솔에서 확인한 상태는 다음과 같다.

- Free plan + **크레딧 $100** (2026-09-26 ~ 2027-09-26), 사용액 $0
- 새 체계에는 "t2/t3.micro 750시간 무료"가 없다. EC2 사용량이 크레딧에서 차감된다.
- 콘솔 안내: **무료 플랜 계정에는 요금이 청구되지 않는다.** 크레딧이 소진되거나 플랜 기간이 끝나면 서비스가 중지된다.

예상 사용량: t3.micro + gp3 16GB = 하루 약 $0.35, 한 달 약 $10. 크레딧으로 충분하다.

| 인스턴스 | 아키텍처 | 월 예상 | 비고 |
|---|---|---|---|
| **t3.micro** (권장) | x86_64 | 약 $9~10 | 로컬 Docker 검증과 같은 아키텍처 |
| t4g.micro | arm64 | 약 $8 | 조금 싸지만 로컬 검증과 환경이 달라진다 |

크레딧이 있으므로 **아키텍처를 로컬과 맞추는 쪽(t3.micro)** 을 택한다.

제출·평가가 끝나면 인스턴스를 종료하고 탄력적 IP를 해제한다(7장).

---

## 0-1. 로컬에서 먼저 검증한다

EC2에 올리기 전에 같은 컨테이너 구성을 로컬에서 돌려 본다. Dockerfile 빌드, Flyway 마이그레이션, 시드 계정 생성,
앱↔MySQL 연결을 여기서 먼저 걸러내면 서버에서 헤맬 일이 줄어든다.

```bash
cd deploy
cp .env.example .env          # 비밀번호는 아무 임의 문자열로 채운다. CANDIDATE_KEY를 넣으면 배경 조회까지 확인할 수 있다
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build mysql app
docker compose logs -f app
```

`http://localhost:8080/login` 로 접속한다. 로컬 오버라이드(`docker-compose.local.yml`)는 Caddy를 띄우지 않고
앱 포트를 직접 열며 `COOKIE_SECURE=false`로 둔다. 로컬 주소로는 HTTPS 인증서를 받을 수 없기 때문이다.

정리:

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml down      # 데이터 유지
docker compose -f docker-compose.yml -f docker-compose.local.yml down -v   # 데이터까지 초기화
```

컨테이너 MySQL은 포트를 밖으로 열지 않으므로, IntelliJ로 쓰는 로컬 개발 DB(3306)와 충돌하지 않는다.

---

## 1. EC2 인스턴스 만들기

1. 리전을 **아시아 태평양(서울) ap-northeast-2** 로 바꾼다.
2. EC2 → 인스턴스 시작
   - 이름: `hr-portal`
   - AMI: **Amazon Linux 2023 (x86_64)**
   - 인스턴스 유형: **t3.micro** (0장 참고)
   - 키 페어: 새로 만들어 `.pem` 파일을 안전한 곳에 둔다
   - 스토리지: **gp3 16GB** (8GB는 도커 이미지까지 담기엔 빠듯하다)
3. 보안 그룹(인바운드)
   | 유형 | 포트 | 소스 | 이유 |
   |---|---|---|---|
   | SSH | 22 | **내 IP** | 관리 접속. 전체 공개하지 않는다 |
   | HTTP | 80 | 0.0.0.0/0 | Let's Encrypt 인증서 발급·갱신 |
   | HTTPS | 443 | 0.0.0.0/0 | 서비스 |

   **3306(MySQL)은 열지 않는다.** DB는 컨테이너 내부 네트워크로만 접근한다.
4. **탄력적 IP**를 할당해 인스턴스에 연결한다. 재부팅해도 주소가 바뀌지 않는다.

## 2. 도메인 연결 (HTTPS에 필요)

세션 쿠키에 `secure`를 켜려면 HTTPS가 있어야 한다. 도메인이 없으면 무료 서브도메인을 쓴다.

1. <https://www.duckdns.org> 로그인 → 원하는 이름 생성(예: `bit-hr-portal`)
2. `current ip`에 1단계의 탄력적 IP를 넣고 update
3. 이제 `bit-hr-portal.duckdns.org`가 서버를 가리킨다

이미 도메인이 있으면 A 레코드를 탄력적 IP로 지정하면 된다.

## 3. 서버 준비 (SSH 접속 후)

```bash
ssh -i <키파일>.pem ec2-user@<탄력적 IP>
```

```bash
# Docker 설치 및 기동
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user      # 적용하려면 한 번 로그아웃 후 재접속

# Docker Compose 플러그인
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version
```

```bash
# swap 2GB — 메모리 1GB에서 JVM과 MySQL이 함께 돌기 때문에 여유를 둔다
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

메모리 배분: JVM 힙 384MB(`app/Dockerfile`) + MySQL 버퍼풀 192MB(`deploy/docker-compose.yml`) + 나머지 OS·Caddy.

## 4. 배포

```bash
git clone <저장소 주소> hr-portal && cd hr-portal/deploy
cp .env.example .env && chmod 600 .env
vi .env          # DOMAIN, 비밀번호 3개, CANDIDATE_KEY 채우기
docker compose up -d --build     # 첫 빌드는 5~10분 걸린다
docker compose ps
docker compose logs -f app
```

앱이 뜨면 Flyway가 테이블과 과제 직원 10명을 만들고, `SeedAccountsRunner`가 관리자(`admin`)와 직원(`emp001`) 계정을 만든다.
로그에 다음 줄이 보이면 정상이다.

```
Background Check 워커 시작: 5초마다 확인한다
```

`CANDIDATE_KEY가 없다`는 경고가 보이면 `.env`에 키가 빠진 것이다. 앱은 뜨지만 배경 조회가 "요청 대기"에서 멈춘다.

브라우저에서 `https://<도메인>/login` 을 연다.

## 5. 배포 후 확인 (제출 전 필수)

| 확인 | 방법 | 기대 |
|---|---|---|
| HTTPS | 주소창 자물쇠 | 인증서 정상. 로그인 후 세션 유지 |
| 관리자 흐름 | admin 로그인 → 직원 목록 → EMP-003 상세 → 배경 조회 요청 | 상세 화면이 "조회 중" → "정상"으로 바뀌고 상세 항목이 보인다 |
| 직원 흐름 | emp001 로그인 → `/me`에서 연락처 수정 | 저장되고, 관리자 화면의 변경 이력에 남는다 |
| 권한 | 직원 계정으로 `/admin/employees` 접근 | 403 |
| 퇴사 처리 | 관리자가 emp001 퇴사 처리 → 직원 화면에서 아무 요청 | 로그인 화면으로 밀린다 |
| 재시작 복구 | `sudo reboot` 후 3분 대기 | 컨테이너 자동 기동, 진행 중이던 조회를 워커가 이어서 처리 |

## 6. 운영 명령

```bash
cd ~/hr-portal/deploy
docker compose logs -f app          # 앱 로그
docker compose logs -f caddy        # 인증서·프록시 로그
docker compose restart app          # 앱만 재시작
docker compose down                 # 전체 중지(데이터 볼륨은 남는다)
```

**코드 갱신**

```bash
cd ~/hr-portal && git pull
cd deploy && docker compose up -d --build app
```

**롤백**: `git checkout <이전 커밋>` 후 같은 명령으로 다시 빌드한다. DB 스키마는 Flyway가 적용한 순서대로 남아 있으므로,
마이그레이션을 추가한 뒤 롤백할 때는 스키마 호환을 먼저 확인한다.

**DB 백업**

```bash
docker compose exec mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" hr_portal' > ~/hr_portal_$(date +%F).sql
```

## 7. 비용 관리

- 제출·평가가 끝나면 **인스턴스 종료(terminate)와 탄력적 IP 해제**를 함께 한다. 탄력적 IP는 연결되지 않은 상태로 두면 따로 과금된다.
- Billing → Budgets에서 월 $10 알림을 걸어 두면 예상치 못한 과금을 빨리 안다.
- 평가 기간에는 끄지 않는다(과제가 제출 후 배포 유지를 요구한다).

## 8. 이 배포에서 알아 둘 한계

- **앱 인스턴스는 1개만** 띄운다. 세션이 메모리에 있어 여러 대로 늘리면 로그인이 풀리고, 워커가 둘이 되면 같은 조회를 두 번 POST할 수 있다(방어 장치는 있지만 설계 전제는 1대다).
- 앱을 재시작하면 로그인 세션이 사라진다. 진행 중이던 배경 조회는 DB에 남아 워커가 이어서 처리한다.
- `measurement/MEASUREMENTS.md`의 측정은 **지원자 PC에서** 한 것이다. 배포 서버(서울 리전)에서의 지연은 다시 재 보지 않으면 알 수 없다.
  확인하려면 서버에서 측정 도구를 그대로 실행한다(도구는 수정하지 않는다).

  ```bash
  sudo dnf install -y python3-pip && pip3 install -r ~/hr-portal/measurement/requirements.txt
  cd ~/hr-portal/measurement && CANDIDATE_KEY=<키> python3 measure_background_api.py latency --samples 20 --timeout 90
  ```

  `runs.csv`에 실행 환경이 함께 기록되므로 로컬 측정분과 구분된다.
