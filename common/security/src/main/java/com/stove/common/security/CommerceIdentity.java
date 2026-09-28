package com.stove.common.security;

import java.util.List;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Validated JWT claims are the only source of a commerce customer's identity. */
public final class CommerceIdentity {

    private CommerceIdentity() {
    }

    public static Long memberId(Jwt jwt) {
        Object claim = jwt == null ? null : jwt.getClaim("member_id");
        if (!(claim instanceof Number number) || number.longValue() <= 0
                || number.doubleValue() != number.longValue()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "member_id claim is required");
        }
        return number.longValue();
    }

    public static JwtAuthenticationConverter rolesConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return roles == null ? List.of() : roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        });
        return converter;
    }

    public static JwtDecoder decoder(String issuer, String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<java.util.List<String>>("aud",
                        audience -> audience != null && audience.contains("esd-api"))));
        return decoder;
    }
}
