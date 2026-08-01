package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 업로드된 빌드(버전). 실제 바이너리는 오브젝트 스토리지에 있고 여기에는 메타데이터만 남는다.
 * (gameId, version) 유니크로 같은 버전의 중복 업로드를 막는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "game_build",
        uniqueConstraints = @UniqueConstraint(name = "uk_build_game_version", columnNames = {"gameId", "version"}))
public class GameBuild extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    @Column(nullable = false, length = 30)
    private String version;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false, length = 300)
    private String storagePath;

    private GameBuild(Long gameId, String version, long fileSize, String checksum, String storagePath) {
        this.gameId = gameId;
        this.version = version;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.storagePath = storagePath;
    }

    public static GameBuild of(Long gameId, String version, long fileSize, String checksum, String storagePath) {
        return new GameBuild(gameId, version, fileSize, checksum, storagePath);
    }
}
