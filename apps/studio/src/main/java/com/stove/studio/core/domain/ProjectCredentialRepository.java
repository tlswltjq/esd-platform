package com.stove.studio.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectCredentialRepository extends JpaRepository<ProjectCredential, Long> {
    Optional<ProjectCredential> findBySecretHash(String secretHash);
    boolean existsByOidcTokenId(String oidcTokenId);
    List<ProjectCredential> findByGameIdOrderByIdDesc(Long gameId);
}
