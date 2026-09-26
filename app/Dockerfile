# 배포용 이미지(DEPLOY.md). 빌드와 실행을 나눈 멀티스테이지다.
# 빌드 단계에는 JDK와 Gradle 캐시가 필요하지만, 실행에는 JRE와 jar만 있으면 된다.
# 그래서 최종 이미지에 소스와 빌드 도구를 남기지 않는다.

# ---- 빌드 ----
# Gradle 배포판 대신 저장소의 래퍼(gradlew)를 쓴다. 로컬에서 테스트한 것과 같은 Gradle 버전으로 빌드하기 위해서다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 빌드 JVM의 힙을 못 박는다. 기본값은 컨테이너에 보이는 메모리의 1/4이라, 1GB 인스턴스(t3.micro)에서는
# 256MB로 잡혀 컴파일 도중 OOM이 날 수 있다. 개발 PC와 서버에서 같은 값으로 빌드하려는 목적도 있다.
ENV GRADLE_OPTS="-Xmx512m"

# 의존성 목록을 먼저 복사해 받아 둔다. 소스만 바뀌면 이 계층은 캐시에서 재사용된다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
# 테스트는 실제 MySQL(hr_portal_test)이 필요하므로 이미지 빌드에서 돌리지 않는다.
# 테스트는 개발 PC에서 `gradlew test`로 돌린 뒤 배포한다(DEPLOY.md 절차).
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- 실행 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 컨테이너 시간대는 UTC로 둔다. 앱은 시각을 항상 UTC로 저장하고, 일정 계산은 DB 시계로 한다(DESIGN.md 2.3).
ENV TZ=UTC
# 1GB 인스턴스에서 MySQL과 함께 돌기 때문에 힙 상한을 둔다(DEPLOY.md의 메모리 배분).
ENV JAVA_TOOL_OPTIONS="-Xmx384m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8"

# root로 돌리지 않는다.
RUN useradd --system --create-home --uid 10001 hrportal
COPY --from=build --chown=hrportal:hrportal /src/build/libs/*.jar /app/app.jar
USER hrportal

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
