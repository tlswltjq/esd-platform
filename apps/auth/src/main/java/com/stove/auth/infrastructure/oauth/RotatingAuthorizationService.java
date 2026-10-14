package com.stove.auth.infrastructure.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps consumed token hashes so replay remains detectable after rotation. */
public final class RotatingAuthorizationService implements OAuth2AuthorizationService, com.stove.auth.core.port.TokenRevocationPort {
    private final OAuth2AuthorizationService delegate;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public RotatingAuthorizationService(OAuth2AuthorizationService delegate, JdbcOperations jdbc,
                                        TransactionTemplate transactions) {
        this.delegate = delegate;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            var refresh = authorization.getRefreshToken();
            if (refresh != null) {
                jdbc.update("""
                        INSERT IGNORE INTO refresh_token_family
                        (authorization_id, client_id, subject, expires_at) VALUES (?, ?, ?, ?)
                        """, authorization.getId(), authorization.getRegisteredClientId(),
                        authorization.getPrincipalName(), Timestamp.from(refresh.getToken().getExpiresAt()));
                lock(authorization.getId());
                String hash = hash(refresh.getToken().getTokenValue());
                jdbc.update("UPDATE refresh_token_history SET consumed = TRUE WHERE authorization_id = ? AND token_hash <> ?",
                        authorization.getId(), hash);
                jdbc.update("INSERT IGNORE INTO refresh_token_history (token_hash, authorization_id) VALUES (?, ?)",
                        hash, authorization.getId());
                if (refresh.isInvalidated()) revoke(authorization.getId());
            }
            delegate.save(authorization);
        });
    }

    public Family familyForToken(String token) {
        List<String> ids = jdbc.query("SELECT authorization_id FROM refresh_token_history WHERE token_hash = ?",
                (rs, row) -> rs.getString(1), hash(token));
        if (ids.isEmpty()) return null;
        Family family = lock(ids.getFirst());
        // Locking read avoids a repeatable-read snapshot hiding another request's rotation.
        boolean consumed = jdbc.query("SELECT consumed FROM refresh_token_history WHERE token_hash = ? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), hash(token)).getFirst();
        return family.withConsumed(consumed);
    }

    private Family lock(String id) {
        return jdbc.query("SELECT * FROM refresh_token_family WHERE authorization_id = ? FOR UPDATE",
                (rs, row) -> new Family(rs.getString("authorization_id"), rs.getString("client_id"),
                        rs.getString("subject"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("revoked"), false), id).getFirst();
    }

    public Instant expiresAt(String id) {
        List<Instant> values = jdbc.query("SELECT expires_at FROM refresh_token_family WHERE authorization_id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        return values.isEmpty() ? null : values.getFirst();
    }

    public void revoke(String id) {
        jdbc.update("UPDATE refresh_token_family SET revoked = TRUE WHERE authorization_id = ?", id);
    }

    public void revokeSubject(String subject) {
        jdbc.update("UPDATE refresh_token_family SET revoked = TRUE WHERE subject = ?", subject);
    }

    public boolean revoked(String id) {
        List<Boolean> values = jdbc.query("SELECT revoked FROM refresh_token_family WHERE authorization_id = ?",
                (rs, row) -> rs.getBoolean(1), id);
        return !values.isEmpty() && values.getFirst();
    }

    @Override public void remove(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            revoke(authorization.getId());
            delegate.remove(authorization);
        });
    }
    @Override public OAuth2Authorization findById(String id) { return delegate.findById(id); }
    @Override public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        OAuth2Authorization result = delegate.findByToken(token, type);
        if (result != null && OAuth2TokenType.REFRESH_TOKEN.equals(type) && revoked(result.getId())) return null;
        return result;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Family(String id, String clientId, String subject, Instant expiresAt,
                         boolean revoked, boolean consumed) {
        public Family withConsumed(boolean consumed) {
            return new Family(id, clientId, subject, expiresAt, revoked, consumed);
        }
    }
}
