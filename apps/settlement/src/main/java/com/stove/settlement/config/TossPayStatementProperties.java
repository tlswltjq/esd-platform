package com.stove.settlement.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.settlement.tosspay")
public record TossPayStatementProperties(boolean enabled,URI baseUrl,String apiKey,String merchantId,String environment,
        String portoneStoreId,String portoneChannelKey,boolean identityMappingVerified,int maxPages,Duration connectTimeout,
        Duration readTimeout,boolean scheduledEnabled,Duration pollDelay,int lookbackDays) {
    public void requireConfigured() {
        boolean loopback=baseUrl!=null && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        boolean provider=baseUrl!=null && "https".equals(baseUrl.getScheme()) && "pay.toss.im".equals(baseUrl.getHost());
        if((!loopback && !provider) || baseUrl.getUserInfo()!=null || baseUrl.getQuery()!=null || baseUrl.getFragment()!=null
                || (baseUrl.getPath()!=null && !baseUrl.getPath().isEmpty() && !"/".equals(baseUrl.getPath()))
                || !text(merchantId) || !text(portoneStoreId) || !text(portoneChannelKey)
                || !("TEST".equals(environment) || "LIVE".equals(environment)) || apiKey==null || apiKey.length()<12
                || !apiKey.startsWith("TEST".equals(environment)?"sk_test_":"sk_live_")
                || maxPages<1 || maxPages>1000 || !positive(connectTimeout) || !positive(readTimeout) || !positive(pollDelay)
                || lookbackDays<1 || lookbackDays>31 || "TEST".equals(environment) && identityMappingVerified)
            throw new IllegalArgumentException("Explicit Toss Pay report endpoint, credential scope and bounded collection settings are required");
    }
    private static boolean positive(Duration value) {return value!=null && !value.isZero() && !value.isNegative();}
    private static boolean text(String value) {return value!=null && !value.isBlank() && value.length()<=100;}
    @Override public String toString() {return "TossPayStatementProperties[credentials=REDACTED]";}
}
