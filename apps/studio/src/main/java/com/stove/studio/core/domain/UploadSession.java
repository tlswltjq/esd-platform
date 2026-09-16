package com.stove.studio.core.domain;

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

@Entity
@Getter
@Table(name = "upload_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long buildId;

    @Column(nullable = false, unique = true, length = 200)
    private String storageUploadId;

    @Column(nullable = false)
    private long partSize;

    @Column(nullable = false)
    private int partCount;

    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadSessionStatus status;

    @Column(nullable = false)
    private Instant expiresAt;

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
