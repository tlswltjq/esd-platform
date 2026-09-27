package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionStatus;

public record RevisionResponse(Long revisionId, int revisionNo, String type, String resolvedPath,
                               String country, String policyVersion, String recommendedRatingCode,
                               StorePageRevisionStatus storePageStatus) {
    public static RevisionResponse from(StorePageRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "STORE_PAGE",
                null, null, null, null, revision.getStatus());
    }
    public static RevisionResponse from(PricingRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "PRICING",
                null, null, null, null, null);
    }
    public static RevisionResponse from(RatingRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "RATING",
                revision.getResolvedPath().name(), revision.getCountry(), revision.getPolicyVersion(),
                revision.getRecommendedRatingCode(), null);
    }
}
