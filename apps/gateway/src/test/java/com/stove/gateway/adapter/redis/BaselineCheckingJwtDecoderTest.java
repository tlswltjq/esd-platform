package com.stove.gateway.adapter.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 거절 기준보다 먼저 발급된 토큰의 거절(R5.7, 결정 180·225). */
class BaselineCheckingJwtDecoderTest {

    private final ReactiveJwtDecoder delegate = mock(ReactiveJwtDecoder.class);
    private final RedisAccessBaselines baselines = mock(RedisAccessBaselines.class);
    private final BaselineCheckingJwtDecoder decoder =
            new BaselineCheckingJwtDecoder(delegate, baselines, Duration.ofSeconds(1));

    @BeforeEach
    void noBaselineByDefault() {
        when(baselines.baseline("usr_1")).thenReturn(Mono.empty());
    }

    private void tokenIssuedAt(String token, Instant issuedAt) {
        when(delegate.decode(token)).thenReturn(Mono.just(Jwt.withTokenValue(token).header("alg", "RS256")
                .subject("usr_1").issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(900)).build()));
    }

    @Test
    @DisplayName("거절 기준이 없으면 그대로 통과한다")
    void passesWithoutABaseline() {
        tokenIssuedAt("t", Instant.parse("2026-11-24T10:00:00Z"));

        StepVerifier.create(decoder.decode("t")).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("iat가 올림한 기준 + 여유값보다 이르면 401(BadJwtException), 같거나 늦으면 통과한다")
    void rejectsTokensIssuedBeforeTheCeiledBaselinePlusSkew() {
        // 기준 10:00:00.300 → 올림 10:00:01, 여유 1초 → 10:00:02 미만은 거절.
        when(baselines.baseline("usr_1")).thenReturn(Mono.just(Instant.parse("2026-11-24T10:00:00.300Z")));
        tokenIssuedAt("old", Instant.parse("2026-11-24T09:59:00Z"));
        tokenIssuedAt("edge", Instant.parse("2026-11-24T10:00:01Z"));
        tokenIssuedAt("fresh", Instant.parse("2026-11-24T10:00:02Z"));

        StepVerifier.create(decoder.decode("old")).expectError(BadJwtException.class).verify();
        StepVerifier.create(decoder.decode("edge")).expectError(BadJwtException.class).verify();
        StepVerifier.create(decoder.decode("fresh")).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("정확히 초에 맞는 기준은 올림하지 않는다")
    void wholeSecondBaselineIsNotCeiled() {
        BaselineCheckingJwtDecoder noSkew = new BaselineCheckingJwtDecoder(delegate, baselines, Duration.ZERO);
        Instant baseline = Instant.parse("2026-11-24T10:00:00Z");

        assertThat(noSkew.issuedBefore(baseline.minusSeconds(1), baseline)).isTrue();
        assertThat(noSkew.issuedBefore(baseline, baseline)).isFalse();
        assertThat(noSkew.issuedBefore(null, baseline)).as("iat 없는 토큰은 기준이 있으면 거절").isTrue();
    }

    @Test
    @DisplayName("기준 저장소에 닿지 못하면 통과시키지 않고 오류를 낸다(R6.3)")
    void storeFailureIsNotABypass() {
        tokenIssuedAt("t", Instant.parse("2026-11-24T10:00:00Z"));
        when(baselines.baseline("usr_1")).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        StepVerifier.create(decoder.decode("t")).expectError(RedisConnectionFailureException.class).verify();
    }
}
