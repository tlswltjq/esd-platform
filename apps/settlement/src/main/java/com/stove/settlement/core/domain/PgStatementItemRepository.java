package com.stove.settlement.core.domain;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgStatementItemRepository extends JpaRepository<PgStatementItem,Long> {
    @EntityGraph(attributePaths="entry") List<PgStatementItem> findByStatementIdOrderByItemPosition(Long statementId);
    @EntityGraph(attributePaths="entry") Page<PgStatementItem> findByStatementId(Long statementId,Pageable page);
    @Query("select i from PgStatementItem i where i.entry.id in :ids and i.id=(select max(j.id) from PgStatementItem j where j.entry.id=i.entry.id)")
    List<PgStatementItem> latestForEntries(@Param("ids") List<Long> ids);
}
