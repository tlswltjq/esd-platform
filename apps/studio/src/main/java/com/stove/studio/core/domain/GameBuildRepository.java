package com.stove.studio.core.domain;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameBuildRepository extends JpaRepository<GameBuild, Long> {

    List<GameBuild> findByGameIdOrderByIdDesc(Long gameId);

    boolean existsByGameIdAndVersion(Long gameId, String version);

    Optional<GameBuild> findByGameIdAndIdempotencyKey(Long gameId, String idempotencyKey);

    @Query("""
            select coalesce(sum(b.fileSize), 0)
              from GameBuild b, GameProject p
             where b.gameId = p.id
               and p.sellerId = :workspaceId
               and b.status in :statuses
            """)
    long sumReservedBytes(@Param("workspaceId") Long workspaceId,
                          @Param("statuses") List<BuildStatus> statuses);

    @Query("""
            select count(b)
              from GameBuild b, GameProject p
             where b.gameId = p.id
               and p.sellerId = :workspaceId
               and b.status in :statuses
            """)
    long countReservedBuilds(@Param("workspaceId") Long workspaceId,
                             @Param("statuses") List<BuildStatus> statuses);

    List<GameBuild> findTop100ByStatusAndUpdatedAtBeforeOrderByIdAsc(
            BuildStatus status, Instant updatedAt);

    Optional<GameBuild> findFirstByActualChecksumAndStatusOrderByIdAsc(
            String actualChecksum, BuildStatus status);
}
