package com.stove.studio.core.domain.revision;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 심사와 공개에 사용할 상점 소개 자료의 판본.
 */
@Entity
@Getter
@Table(name = "store_page_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_store_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorePageRevision extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 같은 프로젝트 내 자료 판본의 순번. */
    @Column(nullable = false) private int revisionNo;
    /** 게임 또는 상점 페이지에 표시할 제목. */
    @Column(nullable = false, length = 200) private String title;
    /** 상점에 노출할 짧은 소개. */
    @Column(nullable = false, length = 500) private String shortDescription;
    /** 상점 페이지에 표시할 상세 소개. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String detailedDescription;
    /** 언어 코드별 제목과 소개 자료를 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String localizationsJson;
    /** 상품에 지정한 장르 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String genresJson;
    /** 검색과 분류에 사용하는 태그 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String tagsJson;
    /** 상점에 표시할 개발사 이름. */
    @Column(nullable = false, length = 200) private String developer;
    /** 상점에 표시할 배급사 이름. */
    @Column(nullable = false, length = 200) private String publisher;
    /** 상점에 표시할 스크린샷 URL 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String screenshotsJson;
    /** 상점에 표시할 소개 영상 URL 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String trailersJson;
    /** 상품 아이콘 이미지 URL. */
    @Column(length = 500) private String iconUrl;
    /** 상품 대표 표지 이미지 URL. */
    @Column(length = 500) private String coverUrl;
    /** 게임이 지원하는 언어 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String supportedLanguagesJson;
    /** 빌드가 실행될 대상 플랫폼. */
    @Column(nullable = false, length = 30) private String platform;
    /** 게임 실행에 필요한 최소 환경. */
    @Column(nullable = false, length = 1000) private String minimumRequirements;
    /** 게임 실행을 권장하는 환경. */
    @Column(nullable = false, length = 1000) private String recommendedRequirements;
    /** 상점에 표시할 지원 기능 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String featuresJson;
    /** 고객 지원 페이지 URL. */
    @Column(nullable = false, length = 500) private String supportUrl;
    /** 개인정보 처리방침 URL. */
    @Column(nullable = false, length = 500) private String privacyPolicyUrl;
    /** 최종 사용자 이용 계약 URL. */
    @Column(nullable = false, length = 500) private String eulaUrl;
    /** 판매를 허용하는 국가 코드 목록을 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String salesCountriesJson;
    /** 국가별 가격 자료를 저장한 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String pricesJson;
    /** 상점 소개 자료의 작성 또는 공개 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private StorePageRevisionStatus status;
    /** 공개 처리를 완료한 시각. */
    private Instant publishedAt;
    /** 동시 수정 충돌을 감지하는 낙관적 잠금 버전. */
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

    /**
     * 상점 소개의 목록과 맵을 저장용 JSON으로 변환한 값.
     *
     * @param localizations 언어 코드별 제목과 소개 자료를 직렬화한 JSON.
     * @param genres 상품에 지정한 장르 목록을 직렬화한 JSON.
     * @param tags 검색과 분류에 사용하는 태그 목록을 직렬화한 JSON.
     * @param screenshots 상점에 표시할 스크린샷 URL 목록을 직렬화한 JSON.
     * @param trailers 상점에 표시할 소개 영상 URL 목록을 직렬화한 JSON.
     * @param supportedLanguages 게임이 지원하는 언어 목록을 직렬화한 JSON.
     * @param features 상점에 표시할 지원 기능 목록을 직렬화한 JSON.
     * @param salesCountries 판매를 허용하는 국가 코드 목록을 직렬화한 JSON.
     * @param prices 국가별 가격 자료를 직렬화한 JSON.
     */
    public record SerializedContent(String localizations, String genres, String tags,
                                    String screenshots, String trailers, String supportedLanguages,
                                    String features, String salesCountries, String prices) {
    }
}
