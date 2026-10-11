package com.stove.common.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 생성/수정 시각 공통 컬럼. 운영 조회·정산 대사 시 기준 시각으로 사용한다. */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    /** 기록이 생성된 시각. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 저장된 기록을 마지막으로 수정한 시각. */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
