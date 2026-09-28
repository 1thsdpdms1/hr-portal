package com.bitcomputer.hrportal.bgcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 외부 API 클라이언트가 실제 HTTP 응답을 어떻게 해석하는지 확인한다.
 *
 * <p>실제 외부 API를 부르지 않는다. 같은 PC에 작은 HTTP 서버를 띄워, 실측에서 관찰한 응답 모양
 * (MEASUREMENTS.md 2-3 의 503 두 종류, 500, 201, 200 상세 등)을 그대로 돌려주게 한다.
 * 측정에서 얻은 사실을 코드가 제대로 다루는지 보는 것이 목적이다.
 */
class HttpBackgroundCheckApiTest {

    private HttpServer server;
    private HttpBackgroundCheckApi api;
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final List<String> receivedKeys = new ArrayList<>();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void 가짜_API_서버를_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Candidate-Key")));
            responder.get().respond(exchange);
        });
        server.start();
        BgCheckProperties properties = new BgCheckProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(35), Duration.ofSeconds(5), Duration.ofSeconds(35), Duration.ofSeconds(20),
            true, Duration.ofSeconds(3),
            Duration.ofSeconds(180), 8, Duration.ofSeconds(5), Duration.ofSeconds(10), 5);
        api = new HttpBackgroundCheckApi(properties, new ObjectMapper());
    }

    @AfterEach
    void 서버를_내린다() {
        server.stop(0);
    }

    private void reply(int status, String body, String... headers) {
        responder.set(exchange -> {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void POST_201이면_checkId와_상태를_읽고_인증키를_헤더로_보낸다() {
        reply(201, """
            {"checkId":"CHK-1","employeeId":"EMP-003","status":"pending",
             "createdAt":"2026-09-22T12:37:44.100Z","message":"ok"}""");

        ApiResult<BackgroundCheckApi.CreatedCheck> result = api.create(
            new BackgroundCheckApi.CreateRequest("EMP-003", "서준", "남궁", LocalDate.of(1988, 7, 21)),
            Duration.ofSeconds(5));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.body().checkId()).isEqualTo("CHK-1");
        assertThat(result.body().status()).isEqualTo(ResultStatus.PENDING);
        assertThat(result.body().createdAt()).isEqualTo(Instant.parse("2026-09-22T12:37:44.100Z"));
        assertThat(receivedKeys).containsExactly("test-key");
    }

    @Test
    void POST_400은_재시도_대상이_아니다() {
        reply(400, """
            {"error":"Bad Request","message":"Missing required field: dateOfBirth","statusCode":400}""");

        ApiResult<BackgroundCheckApi.CreatedCheck> result = api.create(
            new BackgroundCheckApi.CreateRequest("EMP-007", "서연", "이", LocalDate.of(1990, 1, 1)),
            Duration.ofSeconds(5));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isClientError()).isTrue();
        assertThat(result.isTransientFailure()).isFalse();
        assertThat(result.errorCodeOrHttp()).isEqualTo("HTTP_400");
    }

    @Test
    void GET_200_상세_응답은_결과와_상세_항목을_읽는다() {
        // 명세에 없는 tenant·tenantEmployee는 담지 않는다(DESIGN.md 4.3). processingMs는 다음 조회 시각 계산용으로 읽는다(10장).
        reply(200, """
            {"checkId":"CHK-1","employeeId":"EMP-003","status":"clear","criminalRecord":false,
             "educationVerified":true,"employmentVerified":true,"creditScore":"good",
             "createdAt":"2026-09-22T12:37:44.100Z","completedAt":"2026-09-22T12:38:38.601Z",
             "processingMs":54501,"tenant":"x","tenantEmployee":"y"}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.body().status()).isEqualTo(ResultStatus.CLEAR);
        assertThat(result.body().hasDetail()).isTrue();
        assertThat(result.body().criminalRecord()).isFalse();
        assertThat(result.body().creditScore()).isEqualTo("good");
        assertThat(result.body().completedAt()).isEqualTo(Instant.parse("2026-09-22T12:38:38.601Z"));
    }

    @Test
    void GET_200_pending_응답은_상세가_없다고_표시한다() {
        reply(200, """
            {"checkId":"CHK-1","employeeId":"EMP-003","status":"pending","completedAt":null,
             "createdAt":"2026-09-22T12:37:44.100Z","processingMs":54501}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.body().status()).isEqualTo(ResultStatus.PENDING);
        assertThat(result.body().hasDetail()).isFalse();
        assertThat(result.body().completedAt()).isNull();
        // 실측: pending 응답에 이미 최종 처리시간이 들어 있었다(MEASUREMENTS.md 4장, 24/24)
        assertThat(result.body().processingMs()).isEqualTo(54501L);
    }

    @Test
    void processingMs가_없거나_숫자가_아니면_null로_둔다() {
        reply(200, """
            {"checkId":"CHK-1","status":"pending","createdAt":"2026-09-22T12:37:44.100Z"}""");
        assertThat(api.get("CHK-1", Duration.ofSeconds(35)).body().processingMs()).isNull();

        reply(200, """
            {"checkId":"CHK-1","status":"pending","createdAt":"2026-09-22T12:37:44.100Z","processingMs":"54501"}""");
        assertThat(api.get("CHK-1", Duration.ofSeconds(35)).body().processingMs()).isNull();

        reply(200, """
            {"checkId":"CHK-1","status":"pending","createdAt":"2026-09-22T12:37:44.100Z","processingMs":-1}""");
        assertThat(api.get("CHK-1", Duration.ofSeconds(35)).body().processingMs()).isNull();
    }

    @Test
    void 빠른_503은_body의_retryAfter를_읽는다() {
        // 실측: 헤더 0/116, body 72/116(값은 모두 30) — MEASUREMENTS.md 8장
        reply(503, """
            {"error":"Service Unavailable","message":"The service is currently overloaded.",
             "retryAfter":30,"statusCode":503}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.isTransientFailure()).isTrue();
        assertThat(result.errorCodeOrHttp()).isEqualTo("HTTP_503");
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void Retry_After_헤더가_있으면_헤더를_먼저_읽는다() {
        reply(503, """
            {"error":"Service Unavailable","retryAfter":30,"statusCode":503}""", "Retry-After", "12");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void 약_30초형_503은_대기_시간_정보가_없다() {
        // 실측: {"message":"Service Unavailable"}만 오는 종류 44건, Retry-After 0/44 — MEASUREMENTS.md 2-3
        reply(503, """
            {"message":"Service Unavailable"}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.isTransientFailure()).isTrue();
        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void HTTP_500도_재시도_대상이다() {
        reply(500, """
            {"error":"Internal Server Error","message":"Service temporarily unavailable.","statusCode":500}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.isTransientFailure()).isTrue();
        assertThat(result.errorCodeOrHttp()).isEqualTo("HTTP_500");
        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void GET_404는_재시도하지_않는다() {
        reply(404, """
            {"error":"Not Found","message":"Background check not found","statusCode":404}""");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-404", Duration.ofSeconds(35));

        assertThat(result.isClientError()).isTrue();
        assertThat(result.isTransientFailure()).isFalse();
    }

    @Test
    void 응답이_타임아웃보다_늦으면_TIMEOUT으로_기록한다() {
        responder.set(exchange -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofMillis(300));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCodeOrHttp()).isEqualTo(ApiResult.ERROR_TIMEOUT);
        assertThat(result.isTransientFailure()).isTrue();
    }

    @Test
    void 목록_응답에서_checkId만_뽑는다() {
        reply(200, """
            {"employeeId":"EMP-001","totalCount":2,"checks":[
              {"checkId":"CHK-a","status":"clear","createdAt":"2026-09-22T12:00:00Z","completedAt":null},
              {"checkId":"CHK-b","status":"pending","createdAt":"2026-09-22T12:05:00Z","completedAt":null}]}""");

        ApiResult<List<String>> result = api.listCheckIds("EMP-001", Duration.ofSeconds(35));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.body()).containsExactly("CHK-a", "CHK-b");
    }

    @Test
    void JSON이_아닌_2xx_응답은_실패로_다룬다() {
        reply(200, "<html>not json</html>");

        ApiResult<BackgroundCheckApi.CheckDetail> result = api.get("CHK-1", Duration.ofSeconds(35));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCodeOrHttp()).isEqualTo(ApiResult.ERROR_BAD_RESPONSE);
    }
}
