package com.stove.auth.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.common.core.error.BusinessException;
import com.stove.common.event.DomainEvent;
import com.stove.common.event.payload.UserRegisteredEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAccountServiceTest {

    private final UserAccountRepository repository = org.mockito.Mockito.mock(UserAccountRepository.class);
    private final PasswordEncoder passwordEncoder = org.mockito.Mockito.mock(PasswordEncoder.class);
    private final OutboxRecorder outboxRecorder = org.mockito.Mockito.mock(OutboxRecorder.class);
    private UserAccountService service;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(repository, passwordEncoder, outboxRecorder);
    }

    @Test
    @DisplayName("가입은 비밀번호를 해시하고 CREATOR 계정과 등록 이벤트를 함께 만든다")
    void signupCreatesCreatorAndEvent() {
        when(passwordEncoder.encode("very-secret-password")).thenReturn("{bcrypt}hash");
        when(repository.saveAndFlush(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount account = service.signup("Creator@Example.com", "very-secret-password");

        assertThat(account.getEmail()).isEqualTo("creator@example.com");
        assertThat(account.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(account.getRoles()).containsExactly(PlatformRole.CREATOR);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxRecorder).record(eq("UserAccount"), eq(account.getSubject()), event.capture());
        assertThat(event.getValue()).isInstanceOf(UserRegisteredEvent.class);
    }

    @Test
    @DisplayName("이미 확인된 이메일은 저장과 이벤트 발행 전에 충돌로 거절한다")
    void knownDuplicateIsRejected() {
        when(repository.existsByEmailIgnoreCase("creator@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup("creator@example.com", "very-secret-password"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 가입된");
        verify(repository, never()).saveAndFlush(any());
        verify(outboxRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("동시 가입으로 unique 제약이 충돌해도 409 비즈니스 충돌로 수렴한다")
    void concurrentDuplicateIsRejected() {
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
        when(repository.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.signup("creator@example.com", "very-secret-password"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 가입된");
        verify(outboxRecorder, never()).record(any(), any(), any());
    }
}
