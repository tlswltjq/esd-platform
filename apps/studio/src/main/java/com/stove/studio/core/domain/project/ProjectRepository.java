package com.stove.studio.core.domain.project;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Page<Project> findByOwnerSubjectOrderByIdDesc(String subject, Pageable page);

    boolean existsByProductCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id=:id")
    Optional<Project> lock(@Param("id") Long id);
}
