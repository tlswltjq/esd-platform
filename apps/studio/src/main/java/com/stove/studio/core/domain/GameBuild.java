package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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
@Table(name = "game_build")
public class GameBuild extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    @Column(nullable = false, length = 30)
    private String version;

    @Column(nullable = false, length = 100)
    private String buildNumber;

    @Column(nullable = false, length = 30)
    private String platform;

    @Column(nullable = false, length = 30)
    private String architecture;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false, length = 300)
    private String storagePath;

    @Column(length = 64)
    private String actualChecksum;

    private Long actualFileSize;

    @Column(length = 64)
    private String commitSha;

    @Column(length = 300)
    private String repository;

    @Column(length = 300)
    private String sourceRef;

    @Column(length = 30)
    private String ciProvider;

    @Column(length = 100)
    private String ciRunId;

    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    @Column(length = 50)
    private String failureCode;

    private Instant validatedAt;

    /** 동일 checksum의 최초 검증 빌드. 논리 BuildArtifact와 이력은 별도로 유지한다. */
    private Long duplicateOfBuildId;

    @Version
    @Column(nullable = false)
    private long entityVersion;

    private GameBuild(Long gameId, String version, long fileSize, String checksum, String storagePath) {
        this.gameId = gameId;
        this.version = version;
        this.buildNumber = version;
        this.platform = "WINDOWS";
        this.architecture = "X86_64";
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.storagePath = storagePath;
        this.idempotencyKey = "legacy-" + java.util.UUID.randomUUID();
        this.status = BuildStatus.RETIRED;
    }

    public static GameBuild of(Long gameId, String version, long fileSize, String checksum, String storagePath) {
        return new GameBuild(gameId, version, fileSize, checksum, storagePath);
    }

    public static GameBuild uploading(Long gameId, NewUploadSession request, String storagePath) {
        GameBuild build = new GameBuild();
        build.gameId = gameId;
        build.version = request.productVersion();
        build.buildNumber = request.buildNumber();
        build.platform = request.platform();
        build.architecture = request.architecture();
        build.fileSize = request.fileSize();
        build.checksum = request.sha256();
        build.storagePath = storagePath;
        build.commitSha = request.commitSha();
        build.repository = request.repository();
        build.sourceRef = request.sourceRef();
        build.ciProvider = request.ciProvider();
        build.ciRunId = request.ciRunId();
        build.idempotencyKey = request.idempotencyKey();
        build.status = BuildStatus.UPLOADING;
        return build;
    }

    public void processing(long actualFileSize) {
        if (status != BuildStatus.UPLOADING) {
            throw new com.stove.common.core.error.BusinessException(
                    com.stove.common.core.error.ErrorCode.CONFLICT, "업로드 중인 빌드만 완료할 수 있습니다.");
        }
        this.actualFileSize = actualFileSize;
        this.status = BuildStatus.PROCESSING;
    }

    public void validated(String actualChecksum) {
        validated(actualChecksum, null);
    }

    public void validated(String actualChecksum, Long duplicateOfBuildId) {
        if (status != BuildStatus.PROCESSING) {
            throw new com.stove.common.core.error.BusinessException(
                    com.stove.common.core.error.ErrorCode.CONFLICT, "검증 처리 중인 빌드가 아닙니다.");
        }
        this.actualChecksum = actualChecksum;
        this.status = BuildStatus.VALIDATED;
        this.failureCode = null;
        this.validatedAt = Instant.now();
        this.duplicateOfBuildId = duplicateOfBuildId;
    }

    public void fail(String failureCode) {
        if (status != BuildStatus.PROCESSING) {
            return;
        }
        this.status = BuildStatus.FAILED;
        this.failureCode = failureCode;
    }

    public void expireUpload() {
        if (status == BuildStatus.UPLOADING) {
            status = BuildStatus.RETIRED;
            failureCode = "UPLOAD_SESSION_EXPIRED";
        }
    }

    public void retire() {
        if (status == BuildStatus.FAILED || status == BuildStatus.VALIDATED) {
            status = BuildStatus.RETIRED;
        }
    }
}
