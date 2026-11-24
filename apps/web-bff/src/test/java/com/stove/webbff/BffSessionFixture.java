package com.stove.webbff;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stove.webbff.config.bff.BffTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.ReactiveMapSessionRepository;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * 결정 220: Gateway 경로 규칙 테스트는 외부 Bearer가 아니라 BFF 세션으로 호출한다.
 *
 * <p>대신하는 것은 둘이다. (1) OAuth 로그인(Auth 왕복): 요청에 {@link #LOGIN_HEADER}가 있으면 테스트 전용 필터가 세션에
 * 로그인 뒤와 같은 보안 컨텍스트({@code OAuth2AuthenticationToken})와 토큰 기록 키({@code bff.client-key})를 넣는다.
 * (2) 서버 쪽 토큰 기록 조회·갱신: {@link BffTokens#current}를 대역으로 두어 토큰 기록 키(역할 목록)를 그대로 access token으로
 * 돌려준다. 단위 테스트에는 Redis가 없어 세션도 메모리 저장소에 둔다.
 *
 * <p>운영 코드 그대로 거치는 것: 세션에서 보안 컨텍스트 읽기, 세션 인증({@code BffAuthenticationFilter})의 JWT 해석과
 * 역할 판정, 경로 규칙, 하류 Bearer 릴레이({@code BffRelayFilter}), CSRF 검사. 그래서 이 테스트는 "세션 요청의 역할 경계와
 * 릴레이"를 보장하고, Redis 토큰 기록의 읽기·갱신·재사용 탐지는 보장하지 않는다. 그 부분은 두 Gateway와 Auth를 띄운
 * 실제 HTTP 검증 {@code tools/verification/auth-bff.py}가 맡는다(Gateway 통합 테스트도 {@code BffTokens}를 대역으로 둔다).
 * 하류가 받는 헤더는 {@code Bearer <역할 목록>}이고, 테스트의 JWT 해석 대역이 그 값을 역할로 읽는다.
 */
@TestConfiguration
public class BffSessionFixture {
    public static final String LOGIN_HEADER = "X-Test-Bff-Login";
    private static final String TOKEN_KEY_PREFIX = "stove:bff:tokens:";
    private static final ParameterizedTypeReference<Map<String, String>> CSRF_BODY = new ParameterizedTypeReference<>() { };

    /**
     * 단위 테스트에는 Redis가 없다. Spring Session Redis 자동 구성은 세션 저장소 빈이 있으면 물러나므로, 같은 계약의
     * 메모리 저장소를 둔다(Boot 3에서 {@code spring.session.store-type=none}은 더 이상 쓰이지 않는다).
     */
    @Bean
    ReactiveMapSessionRepository reactiveSessionRepository() {
        return new ReactiveMapSessionRepository(new ConcurrentHashMap<>());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter testBffLogin() {
        return (exchange, chain) -> {
            String roles = exchange.getRequest().getHeaders().getFirst(LOGIN_HEADER);
            if (roles == null) {
                return chain.filter(exchange);
            }
            return exchange.getSession().doOnNext(session -> login(session, roles)).then(chain.filter(exchange));
        };
    }

    private static void login(WebSession session, String roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles.split(",")).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, Map.of("sub", "subject"), "sub");
        session.getAttributes().put(WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME,
                new SecurityContextImpl(new OAuth2AuthenticationToken(user, authorities, "gateway-bff")));
        session.getAttributes().put("bff.client-key", roles);
        session.start();
    }

    /** 테스트마다 만든다. {@link BffTokens}는 테스트의 {@code @MockitoBean}이다. */
    public static Sessions sessions(WebTestClient http, BffTokens tokens, ReactiveClientRegistrationRepository registrations) {
        ClientRegistration registration = registrations.findByRegistrationId("gateway-bff").block(Duration.ofSeconds(5));
        when(tokens.current(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0, String.class);
            String access = key.substring(TOKEN_KEY_PREFIX.length());
            Instant now = Instant.now();
            return Mono.just(new OAuth2AuthorizedClient(registration, "subject",
                    new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, now, now.plusSeconds(300), Set.of("commerce"))));
        });
        return new Sessions(http);
    }

    public static final class Sessions {
        private final WebTestClient http;
        private final Map<String, Consumer<HttpHeaders>> byRoles = new HashMap<>();

        private Sessions(WebTestClient http) {
            this.http = http;
        }

        /** 역할 목록(쉼표 구분)으로 로그인한 세션의 쿠키와 그 세션의 CSRF 헤더를 붙인다. */
        public Consumer<HttpHeaders> as(String roles) {
            return byRoles.computeIfAbsent(roles, this::login);
        }

        private Consumer<HttpHeaders> login(String roles) {
            EntityExchangeResult<Map<String, String>> reply = http.get().uri("/bff/csrf").header(LOGIN_HEADER, roles)
                    .exchange().expectStatus().isOk().expectBody(CSRF_BODY).returnResult();
            ResponseCookie cookie = reply.getResponseCookies().getFirst("SESSION");
            Map<String, String> csrf = reply.getResponseBody();
            if (cookie == null || csrf == null) {
                throw new IllegalStateException("BFF 세션 픽스처가 세션 쿠키나 CSRF 토큰을 받지 못했다");
            }
            String session = cookie.getValue();
            String headerName = csrf.get("headerName");
            String token = csrf.get("token");
            return headers -> {
                headers.add(HttpHeaders.COOKIE, "SESSION=" + session);
                headers.set(headerName, token);
            };
        }
    }
}
