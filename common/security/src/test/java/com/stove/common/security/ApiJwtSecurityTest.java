package com.stove.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class ApiJwtSecurityTest {

    private static final String ISSUER = "https://auth.stove.test";
    private static RSAKey signingKey;
    private static HttpServer jwksServer;
    private static JwtDecoder decoder;

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = key();
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        jwksServer.start();
        decoder = ApiJwtSecurity.decoder(ISSUER,
                "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void acceptsSignedApiToken() {
        Jwt jwt = decoder.decode(token(signingKey, ISSUER, List.of("esd-api"), Instant.now().plusSeconds(300)));
        assertThat(CurrentIdentity.memberId(jwt)).isEqualTo(42L);
        assertThat(CurrentIdentity.subject(jwt)).isEqualTo("usr_test");
    }

    @Test
    void rejectsWrongIssuerAudienceAndExpiredToken() {
        for (String token : List.of(
                token(signingKey, "https://other.test", List.of("esd-api"), Instant.now().plusSeconds(300)),
                token(signingKey, ISSUER, List.of("other-api"), Instant.now().plusSeconds(300)),
                token(signingKey, ISSUER, List.of(), Instant.now().plusSeconds(300)),
                token(signingKey, ISSUER, List.of("esd-api"), Instant.now().minusSeconds(120)))) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        String token = token(key(), ISSUER, List.of("esd-api"), Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void mapsRolesAndDoesNotGrantRolesWhenMissing() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256")
                .claim("roles", List.of("MEMBER", "CREATOR")).build();
        assertThat(ApiJwtSecurity.rolesConverter().convert(jwt).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_MEMBER", "ROLE_CREATOR");
        Jwt withoutRoles = Jwt.withTokenValue("test").header("alg", "RS256").subject("usr_test").build();
        assertThat(ApiJwtSecurity.rolesConverter().convert(withoutRoles).getAuthorities()).isEmpty();
    }

    private static String token(RSAKey key, String issuer, List<String> audience, Instant expiry) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("usr_test")
                .issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiry)
                .audience(audience).claim("member_id", 42L).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static RSAKey key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test-key").build();
    }
}
