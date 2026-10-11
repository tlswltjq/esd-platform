package com.stove.studio.core.domain.access;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.studio.core.domain.release.ReleaseChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트의 내부 배포 채널에 대한 테스터 설치 권한.
 */
@Entity
@Getter
@Table(name = "internal_tester_grant", uniqueConstraints = @UniqueConstraint(
        name = "uk_tester_game_subject_channel", columnNames = {"gameId", "testerSubject", "channel"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalTesterGrant extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false) private Long workspaceId;
    /** 내부 테스트 권한을 부여받은 인증 주체. */
    @Column(nullable = false, length = 100) private String testerSubject;
    /** 릴리스를 공개하거나 설치를 허용할 배포 채널. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReleaseChannel channel;
    /** 현재 유효하여 사용할 수 있는지 여부. */
    @Column(nullable = false) private boolean active;

    private InternalTesterGrant(Long gameId, Long workspaceId, String testerSubject, ReleaseChannel channel) {
        this.gameId = gameId;
        this.workspaceId = workspaceId;
        this.testerSubject = testerSubject;
        this.channel = channel;
        this.active = true;
    }

    public static InternalTesterGrant grant(Long gameId, Long workspaceId, String testerSubject,
                                            ReleaseChannel channel) {
        return new InternalTesterGrant(gameId, workspaceId, testerSubject, channel);
    }

    public void activate() {
        active = true;
    }

    public void revoke() {
        active = false;
    }
}
