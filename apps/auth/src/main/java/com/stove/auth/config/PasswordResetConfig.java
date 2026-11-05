package com.stove.auth.config;

import com.stove.auth.adapter.mail.LoggingPasswordResetMailAdapter;
import com.stove.auth.core.domain.PasswordResetTokenRepository;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.PasswordResetMailPort;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.auth.core.service.RecoverPassword;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 분실 비밀번호 복구의 설정 바인딩과 객체 조립. 유효시간은 설정값으로 주입하고(기본 30분),
 * 메일 발송 포트는 모의 구현을 연결한다. 실제 발송 연동이 들어오면 여기서 구현체만 교체한다.
 */
@Configuration
public class PasswordResetConfig {

    @Bean
    PasswordResetMailPort passwordResetMailPort() {
        return new LoggingPasswordResetMailAdapter();
    }

    @Bean
    RecoverPassword recoverPassword(UserAccountRepository accounts, PasswordResetTokenRepository tokens,
            PasswordEncoder passwordEncoder, PasswordResetMailPort mailPort, TokenRevocationPort tokenRevocation,
            @Value("${stove.auth.password-reset.lifetime:PT30M}") Duration lifetime) {
        return new RecoverPassword(accounts, tokens, passwordEncoder, mailPort, tokenRevocation, lifetime);
    }
}
