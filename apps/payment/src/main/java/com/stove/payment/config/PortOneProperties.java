package com.stove.payment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 결정 remove-pg-adapters: PortOne 호출 어댑터가 없어 API 주소·비밀값은 받지 않는다. 남은 값은 결제창 설정과 작업 임대 시간 계산에 쓴다. */
@ConfigurationProperties("stove.payment.portone")
public record PortOneProperties(String storeId, String channelKey, Duration connectTimeout, Duration readTimeout, URI webhookUrl) {
    public void requireConfigured() {
        if (!present(storeId) || !present(channelKey)
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.compareTo(Duration.ofSeconds(60)) < 0
                || webhookUrl == null || !"https".equals(webhookUrl.getScheme()) || webhookUrl.getHost() == null
                || webhookUrl.getUserInfo() != null || webhookUrl.getQuery() != null || webhookUrl.getFragment() != null) {
            throw new IllegalArgumentException("Explicit PortOne test configuration is required");
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
