package com.stove.studio.core.domain.project;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerSubjectOrderByIdDesc(String subject);

    boolean existsByProductCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id=:id")
    Optional<Project> lock(@Param("id") Long id);
}
