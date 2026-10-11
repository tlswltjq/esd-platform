package com.stove.studio.core.domain.project;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 창작자의 프로젝트와 운영 권한을 묶는 작업 공간.
 */
@Entity
@Getter
@Table(name = "workspace")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace extends BaseTimeEntity {

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 작업 공간을 소유한 계정의 인증 주체. */
    @Column(nullable = false, unique = true, length = 100)
    private String ownerSubject;

    /** 작업 공간의 활성 또는 사용 중지 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceStatus status;

    private Workspace(String ownerSubject) {
        this.ownerSubject = ownerSubject;
        this.status = WorkspaceStatus.ACTIVE;
    }

    public static Workspace personal(String ownerSubject) {
        return new Workspace(ownerSubject);
    }
}
