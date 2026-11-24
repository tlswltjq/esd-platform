package com.stove.auth.adapter.oauth;

import com.stove.auth.core.domain.UserAccountRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인가 코드 교환을 한 트랜잭션으로 묶고, 역할을 읽어 토큰을 만들기 전에 계정 행을 공유 잠금한다(AU-T6).
 *
 * <p>잠금이 없으면 교환이 옛 역할을 읽은 뒤 계정 변경의 Refresh 계열 폐기를 지나 새 계열을 저장할 수 있다. 그러면 옛 역할의
 * 토큰이 {@code changedAt} 뒤의 {@code iat}로 나가 API 게이트웨이의 거절도 통과한다. 잠금을 잡으면 교환은 변경보다 먼저
 * 커밋되거나(그 계열은 변경이 폐기한다) 변경 커밋을 기다린 뒤 새 역할을 읽고, {@code iat}도 그 뒤에 정해진다.
 */
public final class SerializedCodeExchangeProvider implements AuthenticationProvider {
    private static final OAuth2TokenType CODE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final UserAccountRepository accounts;
    private final TransactionTemplate transactions;

    public SerializedCodeExchangeProvider(AuthenticationProvider delegate, OAuth2AuthorizationService authorizations,
                                          UserAccountRepository accounts, TransactionTemplate transactions) {
        this.delegate = delegate;
        this.authorizations = authorizations;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override public Authentication authenticate(Authentication authentication) {
        OAuth2AuthorizationCodeAuthenticationToken request = (OAuth2AuthorizationCodeAuthenticationToken) authentication;
        return transactions.execute(status -> {
            OAuth2Authorization authorization = authorizations.findByToken(request.getCode(), CODE);
            if (authorization != null) {
                accounts.lockForIssuance(authorization.getPrincipalName());
            }
            // 코드가 없거나 잘못됐으면 위임 대상이 표준 오류(invalid_grant)를 낸다.
            return delegate.authenticate(authentication);
        });
    }

    @Override public boolean supports(Class<?> authentication) {
        return OAuth2AuthorizationCodeAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
