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

@Entity
@Getter
@Table(name = "store_page_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_store_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorePageRevision extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private int revisionNo;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 500) private String shortDescription;
    @Column(nullable = false, length = 30) private String platform;
    @Column(nullable = false, length = 1000) private String minimumRequirements;

    private StorePageRevision(Long gameId, int revisionNo, String title, String shortDescription,
                              String platform, String minimumRequirements) {
        this.gameId = gameId;
        this.revisionNo = revisionNo;
        this.title = title;
        this.shortDescription = shortDescription;
        this.platform = platform;
        this.minimumRequirements = minimumRequirements;
    }

    public static StorePageRevision create(Long gameId, int revisionNo, String title,
                                           String shortDescription, String platform,
                                           String minimumRequirements) {
        return new StorePageRevision(gameId, revisionNo, title, shortDescription, platform, minimumRequirements);
    }
}
