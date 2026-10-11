package com.stove.studio.core.domain.project;

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

/** A bundle references other projects owned by the same creator. */
@Entity
@Getter
@Table(name = "bundle_component", uniqueConstraints = @UniqueConstraint(
        name = "uk_bundle_component", columnNames = {"bundleGameId", "componentGameId"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BundleComponent {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 구성 관계를 소유한 번들 프로젝트 식별자. */
    @Column(nullable = false) private Long bundleGameId;
    /** 번들에 포함되는 구성 프로젝트 식별자. */
    @Column(nullable = false) private Long componentGameId;

    private BundleComponent(Long bundleGameId, Long componentGameId) {
        this.bundleGameId = bundleGameId;
        this.componentGameId = componentGameId;
    }

    public static BundleComponent of(Long bundleGameId, Long componentGameId) {
        return new BundleComponent(bundleGameId, componentGameId);
    }
}
