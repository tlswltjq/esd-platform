package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
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

@Entity
@Getter
@Table(name = "internal_tester_grant", uniqueConstraints = @UniqueConstraint(
        name = "uk_tester_game_subject_channel", columnNames = {"gameId", "testerSubject", "channel"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalTesterGrant extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false, length = 100) private String testerSubject;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReleaseChannel channel;
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
