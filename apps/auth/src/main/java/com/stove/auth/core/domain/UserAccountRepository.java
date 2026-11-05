package com.stove.auth.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findBySubject(String subject);

    boolean existsByEmailIgnoreCase(String email);

    /** 주어진 역할을 가진 계정 수. 마지막 ADMIN 보호 판정에 쓴다. */
    @Query("SELECT COUNT(a) FROM UserAccount a JOIN a.roles r WHERE r = :role")
    long countByRole(@Param("role") PlatformRole role);
}
