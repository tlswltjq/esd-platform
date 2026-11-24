package com.stove.auth.adapter.oauth;

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
            // 잠금 순서: 계정 행(공유) → 토큰 계열(배타). 계정 변경(배타 잠금 → 계열 폐기)과 같은 순서라 교착하지 않고,
            // 진행 중인 변경이 있으면 그 커밋을 기다린 뒤 폐기된 계열·새 역할을 본다(AU-T6).
            String subject = authorizations.subjectForToken(request.getRefreshToken());
            if (subject == null) return null;
            boolean enabled = accounts.lockForIssuance(subject).filter(account -> account.enabled()).isPresent();
            RotatingAuthorizationService.Family family = authorizations.familyForToken(request.getRefreshToken());
            if (family == null || !family.clientId().equals(client.getRegisteredClient().getId())) return null;
            if (family.revoked()) return null;
            if (family.consumed() || !Instant.now().isBefore(family.expiresAt()) || !enabled) {
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
