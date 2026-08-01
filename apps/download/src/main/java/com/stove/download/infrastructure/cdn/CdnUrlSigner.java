package com.stove.download.infrastructure.cdn;

import com.stove.download.core.domain.SignedUrl;
import com.stove.download.core.port.DownloadUrlSigner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link DownloadUrlSigner} 의 CDN 어댑터.
 * 다운로드 인증을 CDN 엣지에서 끝내기 위한 짧은 수명의 토큰을 만든다
 * (원본 서버가 매 요청을 인증하지 않아도 되게 하는 것이 핵심).
 */
@Component
@ConditionalOnProperty(name = "stove.download.url-strategy", havingValue = "cdn", matchIfMissing = true)
@RequiredArgsConstructor
public class CdnUrlSigner implements DownloadUrlSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final CdnProperties properties;

    @Override
    public SignedUrl sign(String storagePath, Long memberId) {
        Instant expiresAt = Instant.now().plus(properties.ttl());
        String path = storagePath.replaceFirst("^s3://[^/]+/", "");
        String payload = "%s|%d|%d".formatted(path, memberId, expiresAt.getEpochSecond());
        String token = hmac(payload);

        String url = "%s/%s?member=%d&expires=%d&token=%s"
                .formatted(properties.baseUrl(), path, memberId, expiresAt.getEpochSecond(), token);
        return new SignedUrl(url, expiresAt);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(properties.signingKey().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("CDN 서명 실패", e);
        }
    }
}
