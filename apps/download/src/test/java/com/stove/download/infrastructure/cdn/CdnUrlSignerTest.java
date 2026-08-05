package com.stove.download.infrastructure.cdn;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.download.core.domain.SignedUrl;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CDN 서명 URL — <b>운영 기본 어댑터</b>인데 무테스트였다.
 *
 * <p>{@code matchIfMissing = true} 라 설정을 건드리지 않은 모든 환경에서 이 구현이 쓰인다.
 * 그런데 검증돼 있던 것은 비기본 어댑터인 {@code S3PresignedUrlSigner} 쪽뿐이었다.
 *
 * <p>여기서 고정하는 것은 세 가지다 — <b>서명이 무엇을 덮는가</b>,
 * <b>스토리지 접두사가 벗겨지는가</b>, <b>만료가 TTL 을 따르는가</b>.
 * 서명이 덮는 범위가 이 어댑터의 존재 이유다. 경로·회원·만료 중 하나라도 서명 밖에 있으면
 * 토큰을 받은 사람이 그 값을 바꿔 다른 파일이나 다른 사람의 권한으로 넘어갈 수 있다.
 */
class CdnUrlSignerTest {

    private static final String BASE_URL = "https://cdn.stove.test";
    private static final String SIGNING_KEY = "test-signing-key";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final CdnUrlSigner signer =
            new CdnUrlSigner(new CdnProperties(BASE_URL, SIGNING_KEY, TTL));

    /** 프로덕션 구현과 독립적으로 계산한 기대 서명. 구현을 그대로 베끼면 검증이 되지 않는다. */
    private static String expectedToken(String path, long memberId, long expiresAtEpochSecond) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = "%s|%d|%d".formatted(path, memberId, expiresAtEpochSecond);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryParam(String url, String key) {
        for (String pair : URI.create(url).getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key)) {
                return kv[1];
            }
        }
        throw new AssertionError("쿼리 파라미터 없음: " + key);
    }

    @Test
    @DisplayName("서명 URL 은 CDN 베이스 URL 아래 경로를 가리킨다")
    void urlPointsAtTheConfiguredCdn() {
        SignedUrl signed = signer.sign("s3://stove-builds/games/1/1.0.0.pak", 7L);

        assertThat(signed.url()).startsWith(BASE_URL + "/games/1/1.0.0.pak?");
    }

    @Test
    @DisplayName("s3:// 버킷 접두사는 경로에서 벗겨진다")
    void bucketPrefixIsStripped() {
        SignedUrl signed = signer.sign("s3://stove-builds/games/1/1.0.0.pak", 7L);

        // 접두사가 남으면 CDN 이 s3://... 를 경로의 일부로 받아 404 가 된다.
        assertThat(signed.url()).doesNotContain("s3://").doesNotContain("stove-builds");
    }

    @Test
    @DisplayName("접두사가 없는 경로는 그대로 쓴다")
    void plainPathIsUsedAsIs() {
        SignedUrl signed = signer.sign("games/1/1.0.0.pak", 7L);

        assertThat(signed.url()).startsWith(BASE_URL + "/games/1/1.0.0.pak?");
    }

    @Test
    @DisplayName("만료는 TTL 만큼 뒤다")
    void expiryFollowsConfiguredTtl() {
        Instant before = Instant.now();

        SignedUrl signed = signer.sign("games/1/1.0.0.pak", 7L);

        assertThat(signed.expiresAt())
                .isBetween(before.plus(TTL).minusSeconds(5), Instant.now().plus(TTL).plusSeconds(5));
        assertThat(queryParam(signed.url(), "expires"))
                .isEqualTo(String.valueOf(signed.expiresAt().getEpochSecond()));
    }

    @Test
    @DisplayName("TTL 설정을 바꾸면 만료도 따라 바뀐다")
    void ttlComesFromConfiguration() {
        CdnUrlSigner oneHour =
                new CdnUrlSigner(new CdnProperties(BASE_URL, SIGNING_KEY, Duration.ofHours(1)));
        Instant before = Instant.now();

        SignedUrl signed = oneHour.sign("games/1/1.0.0.pak", 7L);

        assertThat(signed.expiresAt()).isAfter(before.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("토큰은 경로·회원·만료를 함께 덮는 HMAC 이다")
    void tokenSignsPathMemberAndExpiry() {
        SignedUrl signed = signer.sign("s3://stove-builds/games/1/1.0.0.pak", 7L);

        String token = queryParam(signed.url(), "token");
        assertThat(token).isEqualTo(
                expectedToken("games/1/1.0.0.pak", 7L, signed.expiresAt().getEpochSecond()));
    }

    @Test
    @DisplayName("회원이 다르면 토큰도 다르다 — member 파라미터만 바꿔치기할 수 없다")
    void tokenIsBoundToTheMember() {
        SignedUrl signed = signer.sign("games/1/1.0.0.pak", 7L);

        // 같은 경로·같은 만료에 회원만 바꿔 계산한 서명과 달라야 한다.
        // 회원이 서명에 안 들어가면 남의 토큰으로 내 계정 다운로드가 된다.
        assertThat(queryParam(signed.url(), "token"))
                .isNotEqualTo(expectedToken(
                        "games/1/1.0.0.pak", 8L, signed.expiresAt().getEpochSecond()));
    }

    @Test
    @DisplayName("경로가 다르면 토큰도 다르다 — 다른 파일로 갈아탈 수 없다")
    void tokenIsBoundToThePath() {
        String first = queryParam(signer.sign("games/1/1.0.0.pak", 7L).url(), "token");
        String second = queryParam(signer.sign("games/2/1.0.0.pak", 7L).url(), "token");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("서명 키가 다르면 토큰도 다르다 — 키가 실제로 쓰인다")
    void tokenDependsOnTheSigningKey() {
        CdnUrlSigner otherKey =
                new CdnUrlSigner(new CdnProperties(BASE_URL, "another-key", TTL));

        SignedUrl signed = otherKey.sign("games/1/1.0.0.pak", 7L);

        // 다른 키로 서명한 URL 은, 같은 입력을 기본 키로 계산한 서명과 달라야 한다.
        // 키가 실제로 쓰이지 않으면 서명은 누구나 재현할 수 있는 값이 된다.
        assertThat(queryParam(signed.url(), "token"))
                .isNotEqualTo(expectedToken(
                        "games/1/1.0.0.pak", 7L, signed.expiresAt().getEpochSecond()));
    }

    @Test
    @DisplayName("설정이 비어 있어도 기본값으로 서명한다")
    void defaultsAreUsable() {
        CdnUrlSigner defaults = new CdnUrlSigner(new CdnProperties(null, null, null));

        SignedUrl signed = defaults.sign("games/1/1.0.0.pak", 7L);

        assertThat(signed.url()).startsWith("https://cdn.stove.local/games/1/1.0.0.pak?");
        assertThat(signed.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(5)));
    }
}
