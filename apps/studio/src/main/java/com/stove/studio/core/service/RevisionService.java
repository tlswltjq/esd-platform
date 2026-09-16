package com.stove.studio.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.PricingRevisionRepository;
import com.stove.studio.core.domain.RatingPath;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.RatingRevisionRepository;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RevisionService {

    private static final String RATING_POLICY_VERSION = "KR-SELF-2026-01";

    private final GameProjectService projectService;
    private final StorePageRevisionRepository storeRepository;
    private final PricingRevisionRepository pricingRepository;
    private final RatingRevisionRepository ratingRepository;
    private final ObjectMapper objectMapper;

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

    public RatingRevision createRating(Long gameId, Long workspaceId, Map<String, Object> questionnaire) {
        projectService.requireOwned(gameId, workspaceId);
        int revision = ratingRepository.findTopByGameIdOrderByRevisionNoDesc(gameId)
                .map(value -> value.getRevisionNo() + 1).orElse(1);
        RatingPath path = resolveRatingPath(questionnaire);
        try {
            return ratingRepository.save(RatingRevision.create(gameId, revision, RATING_POLICY_VERSION,
                    objectMapper.writeValueAsString(questionnaire), path));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("등급 설문을 저장할 수 없습니다.", e);
        }
    }

    private RatingPath resolveRatingPath(Map<String, Object> questionnaire) {
        boolean adultContent = Boolean.TRUE.equals(questionnaire.get("adultContent"));
        boolean cashGambling = Boolean.TRUE.equals(questionnaire.get("cashGambling"));
        return adultContent || cashGambling ? RatingPath.GRAC : RatingPath.SELF_CLASSIFICATION;
    }
}
