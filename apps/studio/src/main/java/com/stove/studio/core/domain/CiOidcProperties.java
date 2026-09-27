package com.stove.studio.core.domain;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stove.ci.oidc")
public record CiOidcProperties(Duration credentialTtl, Map<String, Provider> providers) {
    public CiOidcProperties {
        credentialTtl = credentialTtl == null ? Duration.ofMinutes(15) : credentialTtl;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public record Provider(String issuer, String jwkSetUri, String audience,
                           String repositoryClaim, String refClaim, String environmentClaim) {
    }
}
