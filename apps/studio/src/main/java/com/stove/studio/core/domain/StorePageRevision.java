package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
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
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String detailedDescription;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String localizationsJson;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String genresJson;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String tagsJson;
    @Column(nullable = false, length = 200) private String developer;
    @Column(nullable = false, length = 200) private String publisher;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String screenshotsJson;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String trailersJson;
    @Column(length = 500) private String iconUrl;
    @Column(length = 500) private String coverUrl;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String supportedLanguagesJson;
    @Column(nullable = false, length = 30) private String platform;
    @Column(nullable = false, length = 1000) private String minimumRequirements;
    @Column(nullable = false, length = 1000) private String recommendedRequirements;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String featuresJson;
    @Column(nullable = false, length = 500) private String supportUrl;
    @Column(nullable = false, length = 500) private String privacyPolicyUrl;
    @Column(nullable = false, length = 500) private String eulaUrl;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String salesCountriesJson;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String pricesJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private StorePageRevisionStatus status;
    private Instant publishedAt;
    @Version @Column(nullable = false) private long entityVersion;

    private StorePageRevision(Long gameId, int revisionNo, StorePageContent content,
                              SerializedContent serialized, boolean draft) {
        this.gameId = gameId;
        this.revisionNo = revisionNo;
        apply(content, serialized);
        this.status = draft ? StorePageRevisionStatus.DRAFT : StorePageRevisionStatus.PUBLISHED;
        this.publishedAt = draft ? null : Instant.now();
    }

    public static StorePageRevision create(Long gameId, int revisionNo, StorePageContent content,
                                           SerializedContent serialized, boolean draft) {
        return new StorePageRevision(gameId, revisionNo, content, serialized, draft);
    }

    public void updateDraft(StorePageContent content, SerializedContent serialized) {
        requireDraft();
        apply(content, serialized);
    }

    public void publish() {
        requireDraft();
        status = StorePageRevisionStatus.PUBLISHED;
        publishedAt = Instant.now();
    }

    private void requireDraft() {
        if (status != StorePageRevisionStatus.DRAFT) {
            throw new com.stove.common.core.error.BusinessException(
                    com.stove.common.core.error.ErrorCode.CONFLICT, "발행된 상점 revision은 수정할 수 없습니다.");
        }
    }

    private void apply(StorePageContent content, SerializedContent serialized) {
        this.title = content.title();
        this.shortDescription = content.shortDescription();
        this.detailedDescription = content.detailedDescription();
        this.localizationsJson = serialized.localizations();
        this.genresJson = serialized.genres();
        this.tagsJson = serialized.tags();
        this.developer = content.developer();
        this.publisher = content.publisher();
        this.screenshotsJson = serialized.screenshots();
        this.trailersJson = serialized.trailers();
        this.iconUrl = content.iconUrl();
        this.coverUrl = content.coverUrl();
        this.supportedLanguagesJson = serialized.supportedLanguages();
        this.platform = content.platform();
        this.minimumRequirements = content.minimumRequirements();
        this.recommendedRequirements = content.recommendedRequirements();
        this.featuresJson = serialized.features();
        this.supportUrl = content.supportUrl();
        this.privacyPolicyUrl = content.privacyPolicyUrl();
        this.eulaUrl = content.eulaUrl();
        this.salesCountriesJson = serialized.salesCountries();
        this.pricesJson = serialized.prices();
    }

    public record SerializedContent(String localizations, String genres, String tags,
                                    String screenshots, String trailers, String supportedLanguages,
                                    String features, String salesCountries, String prices) {
    }
}
