package com.stove.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.stove.gateway.config.SecurityConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class GatewayJwtValidationTest {
    @Test
    void rejectsExpiredWrongIssuerWrongAudienceAndTamperedTokens() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("test").generate();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var decoder = new SecurityConfig().jwtDecoder("https://auth.example",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
            var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            Instant now = Instant.now();
            java.util.function.Function<JwtClaimsSet, String> sign = claims -> encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            var valid = JwtClaimsSet.builder().issuer("https://auth.example").subject("member")
                    .audience(List.of("esd-api")).issuedAt(now.minusSeconds(300)).expiresAt(now.plusSeconds(300)).build();
            String token = sign.apply(valid);
            assertThat(decoder.decode(token).block().getSubject()).isEqualTo("member");
            for (var claims : List.of(
                    JwtClaimsSet.from(valid).expiresAt(now.minusSeconds(120)).build(),
                    JwtClaimsSet.from(valid).issuer("https://other.example").build(),
                    JwtClaimsSet.from(valid).audience(List.of("other-api")).build())) {
                assertThatThrownBy(() -> decoder.decode(sign.apply(claims)).block()).isInstanceOf(JwtException.class);
            }
            String[] pieces = token.split("\\.");
            pieces[2] = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
            assertThatThrownBy(() -> decoder.decode(String.join(".", pieces)).block()).isInstanceOf(JwtException.class);
        } finally { server.stop(0); }
    }
}
