package com.stove.auth.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/** JWT 서명키를 DB에 보존해 재시작 후에도 기존 access token을 검증할 수 있게 한다. */
@Component
@DependsOnDatabaseInitialization
@RequiredArgsConstructor
public class SigningKeyStore {

    private static final String ACTIVE_KEY = "active";
    private final JdbcOperations jdbcOperations;

    public RSAKey loadOrCreate() {
        RSAKey existing = find();
        if (existing != null) {
            return existing;
        }
        RSAKey generated = generate();
        try {
            jdbcOperations.update("""
                    INSERT INTO auth_signing_key (key_name, private_jwk, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP(6))
                    """, ACTIVE_KEY, generated.toJSONString());
            return generated;
        } catch (DuplicateKeyException concurrentStartup) {
            RSAKey winner = find();
            if (winner != null) {
                return winner;
            }
            throw concurrentStartup;
        }
    }

    private RSAKey find() {
        return jdbcOperations.query(
                "SELECT private_jwk FROM auth_signing_key WHERE key_name = ?",
                resultSet -> resultSet.next() ? parse(resultSet.getString(1)) : null,
                ACTIVE_KEY);
    }

    private RSAKey parse(String value) {
        try {
            return RSAKey.parse(value);
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException("저장된 JWT 서명키가 올바르지 않습니다.", exception);
        }
    }

    private RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("RSA signing key generation failed", exception);
        }
    }
}
