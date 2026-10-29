package com.stove.payment.config;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment.approval")
public record PaymentApprovalProperties(URI orderBaseUrl,String orderServiceKey,String payloadKey,
        Duration orderConnectTimeout,Duration orderReadTimeout) {
    public PaymentApprovalProperties {
        orderBaseUrl=orderBaseUrl==null?URI.create("http://localhost:8082"):orderBaseUrl;
        orderConnectTimeout=orderConnectTimeout==null?Duration.ofSeconds(2):orderConnectTimeout;
        orderReadTimeout=orderReadTimeout==null?Duration.ofSeconds(5):orderReadTimeout;
    }
    public void requireConfigured() {
        if (orderBaseUrl.getHost()==null || !("http".equals(orderBaseUrl.getScheme()) || "https".equals(orderBaseUrl.getScheme()))
                || orderBaseUrl.getUserInfo()!=null || orderBaseUrl.getQuery()!=null || orderBaseUrl.getFragment()!=null
                || orderServiceKey==null || orderServiceKey.isBlank() || orderConnectTimeout.isNegative() || orderConnectTimeout.isZero()
                || orderReadTimeout.isNegative() || orderReadTimeout.isZero()) throw new IllegalArgumentException("Explicit Order approval service configuration is required");
        decodedPayloadKey();
    }
    public byte[] decodedPayloadKey() {
        try {
            byte[] decoded=Base64.getDecoder().decode(payloadKey==null?"":payloadKey);
            if (decoded.length!=32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Explicit 256-bit approval payload key is required"); }
    }
    @Override public String toString() { return "PaymentApprovalProperties[credentials=redacted]"; }
}
