package com.bitcomputer.hrportal.bgcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link BackgroundCheckApi}의 실제 구현. Java 표준 {@link HttpClient}를 쓴다(DESIGN.md 2.5.2).
 *
 * <p>로그에는 checkId·HTTP 상태·걸린 시간만 남긴다. 인증키·이름·생년월일·상세 결과는 남기지 않는다(DESIGN.md 4.3).
 *
 * <p>503의 대기 시간은 <b>Retry-After 헤더 → 응답 body의 retryAfter</b> 순서로 읽는다.
 * 실측에서 헤더는 0/116건, body는 72/116건이었다(MEASUREMENTS.md 8장). 헤더만 읽는 구현은 한 번도 동작하지 않는다.
 */
@Component
public class HttpBackgroundCheckApi implements BackgroundCheckApi {

    private static final Logger log = LoggerFactory.getLogger(HttpBackgroundCheckApi.class);
    private static final String KEY_HEADER = "X-Candidate-Key";

    private final BgCheckProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpBackgroundCheckApi(BgCheckProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            // 연결 수립까지의 시간. 응답 대기 시간은 요청마다 다르므로 HttpRequest.timeout으로 준다.
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public ApiResult<CreatedCheck> create(CreateRequest request, Duration timeout) {
        String body;
        try {
            body = objectMapper.writeValueAsString(new CreateBody(request.employeeId(), request.firstName(),
                request.lastName(), request.dateOfBirth().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("요청 본문을 만들 수 없다", e);
        }
        HttpRequest httpRequest = newRequest("/background-checks", timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return send("POST /background-checks", httpRequest, timeout, this::parseCreated);
    }

    @Override
    public ApiResult<CheckDetail> get(String checkId, Duration timeout) {
        HttpRequest httpRequest = newRequest("/background-checks/" + encode(checkId), timeout).GET().build();
        return send("GET /background-checks/" + checkId, httpRequest, timeout, this::parseDetail);
    }

    @Override
    public ApiResult<List<String>> listCheckIds(String employeeId, Duration timeout) {
        HttpRequest httpRequest =
            newRequest("/background-checks?employeeId=" + encode(employeeId), timeout).GET().build();
        return send("GET /background-checks?employeeId", httpRequest, timeout, this::parseCheckIds);
    }

    private HttpRequest.Builder newRequest(String path, Duration timeout) {
        if (!properties.hasCandidateKey()) {
            throw new IllegalStateException("환경변수 CANDIDATE_KEY가 없어 외부 API를 호출할 수 없다");
        }
        return HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
            .header(KEY_HEADER, properties.candidateKey())
            .header("Accept", "application/json")
            .timeout(timeout);
    }

    /** 호출 1회. 예외는 여기서 모두 ApiResult로 바꾼다. 워커는 예외 처리 대신 결과 분기만 한다. */
    private <T> ApiResult<T> send(String label, HttpRequest request, Duration timeout, BodyParser<T> parser) {
        Instant start = Instant.now();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Duration elapsed = Duration.between(start, Instant.now());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                T parsed;
                try {
                    parsed = parser.parse(response.body());
                } catch (Exception e) {
                    log.warn("{} 응답을 해석할 수 없다 (status={}, {}ms)", label, status, elapsed.toMillis());
                    return ApiResult.failure(status, ApiResult.ERROR_BAD_RESPONSE, null, elapsed);
                }
                log.info("{} -> {} ({}ms)", label, status, elapsed.toMillis());
                return ApiResult.success(status, parsed, elapsed);
            }
            Duration retryAfter = readRetryAfter(response);
            log.info("{} -> {} ({}ms, retryAfter={})", label, status, elapsed.toMillis(),
                retryAfter == null ? "없음" : retryAfter.toSeconds() + "s");
            return ApiResult.failure(status, "HTTP_" + status, retryAfter, elapsed);
        } catch (HttpTimeoutException e) {
            Duration elapsed = Duration.between(start, Instant.now());
            log.info("{} -> 시간 초과 ({}ms, timeout={}s)", label, elapsed.toMillis(), timeout.toSeconds());
            return ApiResult.failure(0, ApiResult.ERROR_TIMEOUT, null, elapsed);
        } catch (IOException e) {
            Duration elapsed = Duration.between(start, Instant.now());
            log.info("{} -> 네트워크 오류 {} ({}ms)", label, e.getClass().getSimpleName(), elapsed.toMillis());
            return ApiResult.failure(0, ApiResult.ERROR_NETWORK, null, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResult.failure(0, ApiResult.ERROR_NETWORK, null, Duration.between(start, Instant.now()));
        }
    }

    /** 503의 대기 시간: 헤더가 먼저, 없으면 body의 retryAfter(초). */
    private Duration readRetryAfter(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            Duration fromHeader = parseSeconds(header.get());
            if (fromHeader != null) {
                return fromHeader;
            }
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode retryAfter = node.get("retryAfter");
            if (retryAfter != null && retryAfter.isNumber()) {
                return Duration.ofSeconds(retryAfter.asLong());
            }
            if (retryAfter != null && retryAfter.isTextual()) {
                return parseSeconds(retryAfter.asText());
            }
        } catch (Exception e) {
            return null;    // JSON이 아니거나 필드가 없으면 대기 시간 정보가 없는 것으로 본다
        }
        return null;
    }

    private static Duration parseSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            // HTTP 날짜 형식(Retry-After: <http-date>)은 실측에서 관측되지 않았다. 관측되면 그때 다룬다.
            return null;
        }
    }

    private CreatedCheck parseCreated(String body) throws IOException {
        JsonNode node = objectMapper.readTree(body);
        return new CreatedCheck(
            text(node, "checkId"),
            text(node, "employeeId"),
            ResultStatus.fromApiValue(text(node, "status")),
            instant(node, "createdAt"));
    }

    private CheckDetail parseDetail(String body) throws IOException {
        JsonNode node = objectMapper.readTree(body);
        boolean hasDetail = node.hasNonNull("criminalRecord") || node.hasNonNull("educationVerified")
            || node.hasNonNull("employmentVerified") || node.hasNonNull("creditScore");
        return new CheckDetail(
            text(node, "checkId"),
            ResultStatus.fromApiValue(text(node, "status")),
            bool(node, "criminalRecord"),
            bool(node, "educationVerified"),
            bool(node, "employmentVerified"),
            text(node, "creditScore"),
            instant(node, "createdAt"),
            instant(node, "completedAt"),
            hasDetail);
    }

    private List<String> parseCheckIds(String body) throws IOException {
        JsonNode node = objectMapper.readTree(body);
        JsonNode checks = node.get("checks");
        List<String> ids = new ArrayList<>();
        if (checks != null && checks.isArray()) {
            for (JsonNode check : checks) {
                String id = text(check, "checkId");
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** POST 본문. 외부 API 필드 이름에 맞춘다. */
    private record CreateBody(String employeeId, String firstName, String lastName, String dateOfBirth) {
    }

    @FunctionalInterface
    private interface BodyParser<T> {
        T parse(String body) throws Exception;
    }
}
