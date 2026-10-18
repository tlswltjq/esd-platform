package com.stove.payment;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.stove.common.testcontainers.InfraContainers;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.outbox.relay-enabled=false", "stove.payment.processing-enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
public abstract class PaymentIntegrationTestBase {
    static final String ISSUER = "https://payment-test.example";
    static final HttpServer JWKS;
    static final JwtEncoder ENCODER;
    static {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("payment-test").generate();
            ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", e -> {
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
            });
            JWKS.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> JWKS.stop(0)));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void jwt(DynamicPropertyRegistry p) {
        p.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        p.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    static String token(String subject, String role) { return token(subject, role, ISSUER, "esd-api", Instant.now().plusSeconds(300)); }
    static String token(String subject, String role, String issuer, String audience, Instant expires) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expires).claim("roles", List.of(role)).build();
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("payment-test").build(), claims)).getTokenValue();
    }
}
