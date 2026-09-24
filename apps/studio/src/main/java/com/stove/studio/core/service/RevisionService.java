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

    public StorePageRevision createStorePage(Long gameId, Long workspaceId, String title,
                                             String shortDescription, String platform,
                                             String minimumRequirements) {
        projectService.requireOwned(gameId, workspaceId);
        int revision = storeRepository.findTopByGameIdOrderByRevisionNoDesc(gameId)
                .map(value -> value.getRevisionNo() + 1).orElse(1);
        return storeRepository.save(StorePageRevision.create(gameId, revision, title,
                shortDescription, platform, minimumRequirements));
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
}
