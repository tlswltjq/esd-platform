package com.stove.studio.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.studio.core.domain.KoreanRatingPolicy;
import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.PricingRevisionRepository;
import com.stove.studio.core.domain.RatingQuestionnaire;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.RatingRevisionRepository;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import com.stove.studio.core.domain.StorePageContent;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RevisionService {

    private final GameProjectService projectService;
    private final StorePageRevisionRepository storeRepository;
    private final PricingRevisionRepository pricingRepository;
    private final RatingRevisionRepository ratingRepository;
    private final ObjectMapper objectMapper;
    private final KoreanRatingPolicy ratingPolicy;

    public StorePageRevision createStorePage(Long gameId, Long workspaceId, StorePageContent content,
                                             boolean draft) {
        projectService.requireOwned(gameId, workspaceId);
        validateStorePage(content);
        int revision = storeRepository.findTopByGameIdOrderByRevisionNoDesc(gameId)
                .map(value -> value.getRevisionNo() + 1).orElse(1);
        return storeRepository.save(StorePageRevision.create(gameId, revision, content,
                serialize(content), draft));
    }

    public StorePageRevision updateStorePageDraft(Long gameId, Long revisionId, Long workspaceId,
                                                   StorePageContent content) {
        projectService.requireOwned(gameId, workspaceId);
        validateStorePage(content);
        StorePageRevision revision = requireOwnedRevision(gameId, revisionId);
        revision.updateDraft(content, serialize(content));
        return revision;
    }

    public StorePageRevision publishStorePageDraft(Long gameId, Long revisionId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        StorePageRevision revision = requireOwnedRevision(gameId, revisionId);
        revision.publish();
        return revision;
    }

    @Transactional(readOnly = true)
    public StorePageRevision previewStorePage(Long gameId, Long revisionId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        return requireOwnedRevision(gameId, revisionId);
    }

    public PricingRevision createPricing(Long gameId, Long workspaceId, long price) {
        projectService.requireOwned(gameId, workspaceId);
        int revision = pricingRepository.findTopByGameIdOrderByRevisionNoDesc(gameId)
                .map(value -> value.getRevisionNo() + 1).orElse(1);
        return pricingRepository.save(PricingRevision.create(gameId, revision, "KR", "KRW", price));
    }

    public RatingRevision createRating(Long gameId, Long workspaceId, RatingQuestionnaire questionnaire) {
        projectService.requireOwned(gameId, workspaceId);
        int revision = ratingRepository.findTopByGameIdOrderByRevisionNoDesc(gameId)
                .map(value -> value.getRevisionNo() + 1).orElse(1);
        KoreanRatingPolicy.Decision decision = ratingPolicy.evaluate(questionnaire);
        try {
            return ratingRepository.save(RatingRevision.create(gameId, revision, decision.policyVersion(),
                    objectMapper.writeValueAsString(questionnaire), decision.path(), decision.country(),
                    decision.recommendedRatingCode()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("등급 설문을 저장할 수 없습니다.", e);
        }
    }

    private StorePageRevision requireOwnedRevision(Long gameId, Long revisionId) {
        return storeRepository.findById(revisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "storePageRevisionId=" + revisionId));
    }

    private StorePageRevision.SerializedContent serialize(StorePageContent content) {
        try {
            return new StorePageRevision.SerializedContent(
                    objectMapper.writeValueAsString(content.localizations()),
                    objectMapper.writeValueAsString(content.genres()),
                    objectMapper.writeValueAsString(content.tags()),
                    objectMapper.writeValueAsString(content.screenshots()),
                    objectMapper.writeValueAsString(content.trailers()),
                    objectMapper.writeValueAsString(content.supportedLanguages()),
                    objectMapper.writeValueAsString(content.features()),
                    objectMapper.writeValueAsString(content.salesCountries()),
                    objectMapper.writeValueAsString(content.prices()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("상점 페이지를 저장할 수 없습니다.", exception);
        }
    }

    private void validateStorePage(StorePageContent content) {
        requireHttps(content.screenshots());
        requireHttps(content.trailers());
        requireHttps(java.util.Arrays.asList(content.iconUrl(), content.coverUrl(), content.supportUrl(),
                content.privacyPolicyUrl(), content.eulaUrl()));
        boolean invalidCountry = content.salesCountries().stream()
                .anyMatch(value -> value == null || !value.matches("[A-Z]{2}"));
        boolean invalidPrice = content.prices().entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !entry.getKey().matches("[A-Z]{3}")
                        || entry.getValue() == null || entry.getValue() < 0);
        if (invalidCountry || invalidPrice) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "판매 국가는 ISO 2자리, 통화는 ISO 3자리와 0 이상의 가격이어야 합니다.");
        }
        for (Map.Entry<String, StorePageContent.LocalizedContent> entry : content.localizations().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z]{2}(-[A-Z]{2})?")) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "언어 코드는 BCP 47 형식이어야 합니다.");
            }
        }
    }

    private void requireHttps(Collection<String> urls) {
        if (urls.stream().filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> !value.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "게시 자산과 정책 URL은 HTTPS여야 합니다.");
        }
    }
}
