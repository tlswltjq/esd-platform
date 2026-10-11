package com.stove.studio.core.domain.build;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 빌드 파일의 분할 업로드 진행과 만료를 관리하는 세션.
 */
@Entity
@Getter
@Table(name = "upload_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadSession extends BaseTimeEntity {

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false, unique = true)
    private Long buildId;

    /** 객체 저장소가 발급한 분할 업로드 식별자. */
    @Column(nullable = false, unique = true, length = 200)
    private String storageUploadId;

    /** 분할 업로드 조각 하나의 기준 크기(바이트). */
    @Column(nullable = false)
    private long partSize;

    /** 업로드를 완료하는 데 필요한 조각 수. */
    @Column(nullable = false)
    private int partCount;

    /** 같은 요청의 중복 처리를 방지하는 키. */
    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    /** 분할 업로드의 진행·완료·만료·중단 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadSessionStatus status;

    /** 업로드를 완료해야 하는 세션 만료 시각. */
    @Column(nullable = false)
    private Instant expiresAt;

    /** 분할 업로드를 완료한 시각. */
    private Instant completedAt;

    private UploadSession(Long buildId, String storageUploadId, long partSize, int partCount,
                          String idempotencyKey, Instant expiresAt) {
        this.buildId = buildId;
        this.storageUploadId = storageUploadId;
        this.partSize = partSize;
        this.partCount = partCount;
        this.idempotencyKey = idempotencyKey;
        this.status = UploadSessionStatus.OPEN;
        this.expiresAt = expiresAt;
    }

    public static UploadSession open(Long buildId, String storageUploadId, long partSize,
                                     int partCount, String idempotencyKey, Instant expiresAt) {
        return new UploadSession(buildId, storageUploadId, partSize, partCount, idempotencyKey, expiresAt);
    }

    public void complete() {
        if (status == UploadSessionStatus.COMPLETED) {
            return;
        }
        if (status != UploadSessionStatus.OPEN || expiresAt.isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "완료할 수 없는 업로드 세션입니다.");
        }
        status = UploadSessionStatus.COMPLETED;
        completedAt = Instant.now();
    }

    public void expire() {
        if (status == UploadSessionStatus.OPEN) {
            status = UploadSessionStatus.EXPIRED;
        }
    }
}
