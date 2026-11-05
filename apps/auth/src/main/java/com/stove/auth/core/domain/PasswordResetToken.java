package com.stove.auth.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분실 비밀번호 복구를 위한 일회용 토큰. 원본은 저장하지 않고 SHA-256 해시만 보관한다.
 *
 * <p>토큰은 한 번만 쓸 수 있고(소비되면 재사용 불가), 발급 시각부터 고정된 유효시간을 가진다.
 * 유효성 검증은 의미별 메서드({@link #usable(Instant)})로 표현한다.
 */
@Entity
@Getter
@Table(name = "password_reset_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    /** 복구 토큰 원본의 SHA-256 해시. 토큰을 식별하는 기본 키. 저장은 고정 길이 CHAR(64). */
    @Id
    @Column(name = "token_hash", columnDefinition = "CHAR(64)", nullable = false)
    private String tokenHash;

    /** 복구 대상 계정을 식별하는 인증 주체 문자열. */
    @Column(nullable = false, length = 64)
    private String subject;

    /** 토큰이 더 이상 유효하지 않게 되는 시각. */
    @Column(nullable = false)
    private Instant expiresAt;

    /** 토큰이 이미 사용되었는지 여부. 사용된 토큰은 다시 쓸 수 없다. */
    @Column(nullable = false)
    private boolean consumed;

    private PasswordResetToken(String tokenHash, String subject, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.subject = subject;
        this.expiresAt = expiresAt;
        this.consumed = false;
    }

    /**
     * 복구 토큰을 발급한다.
     *
     * @param tokenHash 토큰 원본의 SHA-256 해시(64자 16진수).
     * @param subject   복구 대상 계정의 인증 주체.
     * @param issuedAt  발급 시각.
     * @param lifetime  발급 시각부터의 유효시간(초). 양수여야 한다.
     */
    public static PasswordResetToken issue(String tokenHash, String subject, Instant issuedAt, long lifetimeSeconds) {
        if (tokenHash == null || tokenHash.length() != 64) {
            throw new IllegalArgumentException("토큰 해시는 64자 16진수여야 합니다.");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("복구 대상 subject는 비어 있을 수 없습니다.");
        }
        if (lifetimeSeconds <= 0) {
            throw new IllegalArgumentException("유효시간은 양수여야 합니다.");
        }
        return new PasswordResetToken(tokenHash, subject, issuedAt.plusSeconds(lifetimeSeconds));
    }

    /** 지금 이 토큰을 복구에 쓸 수 있는가. 소비되지 않았고 만료되지 않아야 한다. */
    public boolean usable(Instant now) {
        return !consumed && now.isBefore(expiresAt);
    }

    /** 토큰을 사용 처리한다. 복구가 끝나면 호출해 재사용을 막는다. */
    public void consume() {
        this.consumed = true;
    }
}
