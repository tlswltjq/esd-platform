package com.stove.studio.core.domain.build;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false)
    private Long gameId;

    /** 배포 파일의 제품 버전 문자열. */
    @Column(nullable = false, length = 30)
    private String version;

    /** 같은 제품 버전 내 빌드를 구별하는 번호. */
    @Column(nullable = false, length = 100)
    private String buildNumber;

    /** 빌드가 실행될 대상 플랫폼. */
    @Column(nullable = false, length = 30)
    private String platform;

    /** 빌드가 실행될 CPU 아키텍처. */
    @Column(nullable = false, length = 30)
    private String architecture;

    /** 델타 패치를 적용할 기준 버전으로, 없으면 전체 파일. */
    @Column(length = 30)
    private String deltaFromVersion;

    /** 업로드 요청에서 선언한 배포 파일 크기(바이트). */
    @Column(nullable = false)
    private long fileSize;

    /** 업로드 요청에서 선언한 SHA-256 체크섬. */
    @Column(nullable = false, length = 64)
    private String checksum;

    /** 파일이 보관된 객체 저장소 경로. */
    @Column(nullable = false, length = 300)
    private String storagePath;

    /** 저장된 파일에서 실제로 계산한 체크섬. */
    @Column(length = 64)
    private String actualChecksum;

    /** 저장된 파일에서 실제로 확인한 크기(바이트). */
    private Long actualFileSize;

    /** 빌드에 사용한 소스 커밋 식별자. */
    @Column(length = 64)
    private String commitSha;

    /** 빌드를 생성한 소스 저장소. */
    @Column(length = 300)
    private String repository;

    /** 빌드에 사용한 브랜치 또는 태그 참조. */
    @Column(length = 300)
    private String sourceRef;

    /** 빌드를 실행한 CI 제공자. */
    @Column(length = 30)
    private String ciProvider;

    /** CI 빌드 실행을 추적할 식별자. */
    @Column(length = 100)
    private String ciRunId;

    /** 같은 요청의 중복 처리를 방지하는 키. */
    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    /** 파일 업로드·검증·퇴역 진행 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    /** 빌드 검증 실패의 분류 코드. */
    @Column(length = 50)
    private String failureCode;

    /** 빌드 파일 검증을 마친 시각. */
    private Instant validatedAt;

    /** 동일 checksum의 최초 검증 빌드. 논리 BuildArtifact와 이력은 별도로 유지한다. */
    private Long duplicateOfBuildId;

    /** 동시 수정 충돌을 감지하는 낙관적 잠금 버전. */
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
        build.deltaFromVersion = request.deltaFromVersion();
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
