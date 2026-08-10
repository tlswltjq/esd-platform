package com.stove.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 스택을 두드리는 HTTP 클라이언트.
 *
 * <p><b>4xx 를 예외로 만들지 않는다.</b> 인수 45건 중 7건이 실패를 기대하는 판정이고
 * ({@code PRICE_MISMATCH} 409, {@code PAYMENT_TX_MISMATCH} 409, 미보유 403, result 누락 400 …),
 * 그 7건이 이 층에서 가장 값이 큰 부분이다 — 성공 경로는 대부분 아래 층에도 방어선이 있지만
 * 서비스 <i>사이</i> 의 거절은 여기서만 확인된다. {@link RestClient} 의 기본 동작(4xx/5xx 에 예외)을
 * 그대로 쓰면 <b>기대한 실패와 진짜 실패가 같은 모양</b>이 되므로 {@code exchange} 로 받는다.
 *
 * <p>응답은 봉투째 들고 다닌다({@code success}/{@code data}/{@code error}). 상태코드만 보던
 * 셸과 달리 {@code error.code} 까지 대조할 수 있고, 그래야 "409 이긴 한데 다른 이유로 409" 를 가른다.
 */
public final class E2eClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;

    public E2eClient(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        // 스택이 응답하지 않는 것을 영원히 기다리지 않는다. 전파 대기는 Await 가 폴링으로 하고,
        // 한 번의 호출이 오래 걸리는 것은 그 자체로 신호다.
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Response get(String path) {
        return send(HttpMethod.GET, path, null, Map.of());
    }

    public Response get(String path, Map<String, String> headers) {
        return send(HttpMethod.GET, path, null, headers);
    }

    public Response post(String path, Object body) {
        return send(HttpMethod.POST, path, body, Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        return send(HttpMethod.POST, path, body, headers);
    }

    private Response send(HttpMethod method, String path, Object body, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path);
        headers.forEach(spec::header);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) ->
                new Response(response.getStatusCode().value(), read(response.getBody()), response.getHeaders()));
    }

    /** 본문이 비어 있어도 단언이 NPE 로 죽지 않게 빈 노드를 돌려준다 — 상태코드 판정은 본문과 무관하다. */
    private static JsonNode read(InputStream in) {
        try {
            byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
            return bytes.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("응답 본문을 읽지 못했다", e);
        }
    }

    /**
     * 응답 하나.
     *
     * @param status  HTTP 상태코드
     * @param body    봉투 전체 ({@code success}/{@code data}/{@code error})
     * @param headers 응답 헤더 — {@code X-Correlation-Id} 로 traceId 가 돌아온다
     */
    public record Response(int status, JsonNode body, HttpHeaders headers) {

        /** 봉투를 벗긴 알맹이. 없으면 {@code MissingNode} 라 {@code path()} 체인이 계속 살아 있다. */
        public JsonNode data() {
            return body.path("data");
        }

        /** 실패 응답의 도메인 오류 코드. 성공이면 빈 문자열이다. */
        public String errorCode() {
            return body.path("error").path("code").asText("");
        }

        /**
         * {@code data} 배열에서 필드 값이 일치하는 첫 원소. 없으면 {@link MissingNode} 라
         * 뒤따르는 {@code path()} 체인이 NPE 없이 이어진다 — <b>"없다" 도 단언할 수 있는 값</b>이다.
         *
         * <p>목록 응답이 전부 평평한 배열이라 이 하나로 충분하다(페이지 봉투가 아니다).
         */
        public JsonNode itemWhere(String field, String value) {
            for (JsonNode item : data()) {
                if (value.equals(item.path(field).asText())) {
                    return item;
                }
            }
            return MissingNode.getInstance();
        }

        /** 판정이 깨졌을 때 화면에 남길 한 줄. 셸이 본문 120자를 잘라 붙이던 자리다. */
        @Override
        public String toString() {
            String raw = body.toString();
            return "HTTP " + status + " " + (raw.length() > 300 ? raw.substring(0, 300) + "…" : raw);
        }
    }
}
