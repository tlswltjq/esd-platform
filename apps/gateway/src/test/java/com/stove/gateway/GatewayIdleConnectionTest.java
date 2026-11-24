package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import com.stove.gateway.config.bff.BffTokens;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

/**
 * [D-053] 하류가 닫는 유휴 연결을 Gateway가 다시 써 요청이 응답 전에 끊기고 500이 나면 안 된다.
 *
 * <p>야간 CI의 31분 대기 여정이 60초마다 주문을 조회한다. 하류 Tomcat은 keep-alive 유휴 연결을 60초에 닫는다
 * ({@code connectionTimeout} 기본값). Gateway 연결 풀의 유휴 상한({@code max-idle-time})이 없으면, Tomcat이 닫는 바로 그
 * 순간 Gateway가 그 연결을 골라 요청을 보내고 {@code PrematureCloseException}으로 500이 된다(2026-11-10, 11-22 재발).
 *
 * <p><b>경합을 결정적으로 만든다.</b> 실제로는 "닫는 순간"과 "다시 쓰는 순간"이 겹칠 때만 실패한다. 여기 가짜 하류는
 * 조금이라도({@link #DOWNSTREAM_STALE}) 쉰 연결로 들어온 요청을 응답 없이 끊는다 — 늘 그 경합 한가운데에 있는 하류다.
 * Gateway가 자기 유휴 상한을 넘긴 연결을 버리고 새 연결을 쓰면 이 하류도 정상 응답한다.
 *
 * <p>운영 설정({@code application.yml})을 그대로 태운다. 대기 시간은 묶인 {@code max-idle-time}보다 길게 잡으므로
 * 값이 바뀌어도 테스트가 따라간다. 값이 없으면(수정 전) 30초를 기다려 실패를 본다. 수정 전에는 두 번째 요청이 500이었다(2026-11-23 Red).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BffSessionFixture.class)
@DisplayName("Gateway — 하류 유휴 연결 재사용")
class GatewayIdleConnectionTest {

    private static final String PATH = "/api/v1/payments/11111111-1111-1111-1111-111111111111/approvals";
    private static final byte[] BODY = "{\"success\":true,\"data\":{\"content\":[]}}".getBytes(StandardCharsets.UTF_8);
    /** 가짜 하류가 "이미 닫았다"고 보는 유휴 시간. Gateway 유휴 상한보다 훨씬 짧다. */
    private static final Duration DOWNSTREAM_STALE = Duration.ofSeconds(1);
    /** 유휴 상한이 없을 때(수정 전) 기다리는 시간. */
    private static final Duration WITHOUT_LIMIT = Duration.ofSeconds(30);

    private static final AtomicInteger CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static final ServerSocket DOWNSTREAM;

    static {
        try {
            DOWNSTREAM = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(GatewayIdleConnectionTest::accept, "fake-downstream");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Autowired
    WebTestClient http;
    @Autowired
    HttpClientProperties httpClient;
    @MockitoBean
    ReactiveJwtDecoder decoder;
    @MockitoBean BffTokens tokens;
    @Autowired ReactiveClientRegistrationRepository registrations;
    private BffSessionFixture.Sessions sessions;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry properties) {
        properties.add("PAYMENT_URI", () -> "http://127.0.0.1:" + DOWNSTREAM.getLocalPort());
    }

    @BeforeEach
    void member() {
        sessions = BffSessionFixture.sessions(http, tokens, registrations);
        CONNECTIONS.set(0);
        DROPPED.set(0);
        when(decoder.decode(anyString())).thenAnswer(call -> Mono.just(Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("usr_buyer").claim("roles", List.of("MEMBER")).build()));
    }

    @Test
    @DisplayName("[D-053] 유휴 상한을 넘겨 쉰 하류 연결은 다시 쓰지 않는다 — 두 번째 요청도 200")
    void aConnectionIdleBeyondThePoolLimitIsNotReused() throws InterruptedException {
        get();
        Duration limit = httpClient.getPool().getMaxIdleTime();
        Thread.sleep((limit == null ? WITHOUT_LIMIT : limit).plusSeconds(2).toMillis());

        // 기대: Gateway가 유휴 상한을 넘긴 연결을 버리고 새 연결로 보낸다.
        // 수정 전: 상한이 없어 쉰 연결을 다시 쓰고, 하류가 응답 없이 끊어 500이 난다.
        // 영향: 조회는 다시 부르면 회복되지만 POST(결제 승인 접수 등)는 클라이언트가 처리 여부를 모른다.
        get();
        assertThat(DROPPED.get()).as("Gateway가 하류가 닫은 쉰 연결로 요청을 보냈다").isZero();
        assertThat(CONNECTIONS.get()).as("두 번째 요청은 새 연결이어야 한다").isEqualTo(2);
    }

    private void get() {
        http.get().uri(PATH).headers(sessions.as("MEMBER")).exchange().expectStatus().isOk();
    }

    private static void accept() {
        while (!DOWNSTREAM.isClosed()) {
            try {
                Socket socket = DOWNSTREAM.accept();
                CONNECTIONS.incrementAndGet();
                Thread handler = new Thread(() -> serve(socket), "fake-downstream-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    /** keep-alive로 요청을 계속 받되, 쉰 연결로 온 요청은 응답 없이 끊는다. */
    private static void serve(Socket socket) {
        try (Socket connection = socket) {
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            long lastResponse = -1;
            while (readRequestHead(in)) {
                long now = System.nanoTime();
                if (lastResponse >= 0 && Duration.ofNanos(now - lastResponse).compareTo(DOWNSTREAM_STALE) >= 0) {
                    DROPPED.incrementAndGet();
                    return;
                }
                String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                        + "Content-Length: " + BODY.length + "\r\nConnection: keep-alive\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(BODY);
                out.flush();
                lastResponse = System.nanoTime();
            }
        } catch (IOException ignored) {
            // 연결이 끊기면 그 연결의 처리를 끝낸다.
        }
    }

    /** 본문 없는 GET만 받는다. 머리 끝(빈 줄)까지 읽으면 true, 연결이 닫히면 false. */
    private static boolean readRequestHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int next = in.read();
            if (next < 0) {
                return false;
            }
            head.write(next);
            matched = next == end[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        return true;
    }
}
