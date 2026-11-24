package com.stove.auth.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findBySubject(String subject);

    /**
     * 계정 행을 배타 잠금(`FOR UPDATE`)으로 읽는다. 계정 상태·역할·비밀번호를 바꾸고 Refresh 계열을 폐기하는 쓰기는 모두
     * 이것으로 시작한다(계정 행 → 토큰 계열 순서, AU-T6). 같은 계정의 토큰 발급({@link #lockForIssuance})과 직렬화된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserAccount a WHERE a.subject = :subject")
    Optional<UserAccount> lockForChange(@Param("subject") String subject);

    /**
     * 토큰 발급(코드 교환·갱신)이 역할을 읽기 전에 계정 행을 공유 잠금(`FOR SHARE`)으로 읽는다. 발급은 진행 중인 계정 변경이
     * 커밋된 뒤 새 역할을 읽거나, 변경보다 먼저 끝나 그 계열이 변경의 폐기 대상이 된다(AU-T6).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM UserAccount a WHERE a.subject = :subject")
    Optional<UserAccount> lockForIssuance(@Param("subject") String subject);

    boolean existsByEmailIgnoreCase(String email);

    /** 주어진 역할을 가진 계정 수. 마지막 ADMIN 보호 판정에 쓴다. */
    @Query("SELECT COUNT(a) FROM UserAccount a JOIN a.roles r WHERE r = :role")
    long countByRole(@Param("role") PlatformRole role);
}
