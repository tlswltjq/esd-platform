package com.stove.gateway.adapter.redis;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 서명·issuer·audience·만료를 확인한 뒤, 그 subject의 거절 기준보다 먼저 발급된 토큰을 거절한다(R5.7, 결정 180·225).
 *
 * <p>거절: {@code iat} &lt; 올림(기준, 초) + 여유값. {@code iat}는 초 단위라 기준을 올림하고, Auth 인스턴스 간 시계 오차는
 * 여유값으로 흡수한다(Auth design 시계 전제, PR #244 R3). 대가로 변경 직후 그만큼 새로 받은 토큰도 거절될 수 있다. 거절은
 * {@link BadJwtException}이라 401이다. 기준 저장소(Redis)에 닿지 못하면 그 오류를 그대로 내 인증을 우회하지 않는다(R6.3).
 */
public class BaselineCheckingJwtDecoder implements ReactiveJwtDecoder {

    private final ReactiveJwtDecoder delegate;
    private final RedisAccessBaselines baselines;
    private final Duration clockSkew;

    public BaselineCheckingJwtDecoder(ReactiveJwtDecoder delegate, RedisAccessBaselines baselines, Duration clockSkew) {
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("시계 오차 여유는 음수일 수 없다: " + clockSkew);
        }
        this.delegate = delegate;
        this.baselines = baselines;
        this.clockSkew = clockSkew;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token).flatMap(jwt -> jwt.getSubject() == null ? Mono.just(jwt)
                : baselines.baseline(jwt.getSubject())
                        .flatMap(baseline -> issuedBefore(jwt.getIssuedAt(), baseline)
                                ? Mono.<Jwt>error(new BadJwtException("계정 접근이 바뀌기 전에 발급된 토큰입니다."))
                                : Mono.just(jwt))
                        .switchIfEmpty(Mono.just(jwt)));
    }

    boolean issuedBefore(Instant issuedAt, Instant baseline) {
        if (issuedAt == null) {
            return true;
        }
        long ceiled = baseline.getEpochSecond() + (baseline.getNano() > 0 ? 1 : 0);
        return issuedAt.getEpochSecond() < ceiled + clockSkew.toSeconds();
    }
}
