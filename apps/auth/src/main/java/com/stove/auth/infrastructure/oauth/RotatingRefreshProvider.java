package com.stove.auth.infrastructure.oauth;

import com.stove.auth.core.domain.UserAccountRepository;
import java.time.Instant;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes refresh grants across auth instances, committing replay revocation before returning an error. */
public final class RotatingRefreshProvider implements AuthenticationProvider {
    private final AuthenticationProvider delegate;
    private final RotatingAuthorizationService authorizations;
    private final UserAccountRepository accounts;
    private final TransactionTemplate transactions;

    public RotatingRefreshProvider(AuthenticationProvider delegate, RotatingAuthorizationService authorizations,
                                   UserAccountRepository accounts, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.authorizations = authorizations;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override public Authentication authenticate(Authentication authentication) {
        OAuth2RefreshTokenAuthenticationToken request = (OAuth2RefreshTokenAuthenticationToken) authentication;
        if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                || !client.isAuthenticated() || client.getRegisteredClient() == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        Authentication result = transactions.execute(status -> {
            var family = authorizations.familyForToken(request.getRefreshToken());
            if (family == null || !family.clientId().equals(client.getRegisteredClient().getId())) return null;
            if (family.revoked()) return null;
            if (family.consumed() || !Instant.now().isBefore(family.expiresAt())
                    || accounts.findBySubject(family.subject()).filter(account -> account.enabled()).isEmpty()) {
                authorizations.revoke(family.id());
                return null;
            }
            return delegate.authenticate(authentication);
        });
        if (result == null) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        return result;
    }

    @Override public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
