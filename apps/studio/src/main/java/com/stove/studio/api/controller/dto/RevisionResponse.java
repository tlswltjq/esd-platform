package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.StorePageRevision;

public record RevisionResponse(Long revisionId, int revisionNo, String type, String resolvedPath) {
    public static RevisionResponse from(StorePageRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "STORE_PAGE", null);
    }
    public static RevisionResponse from(PricingRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "PRICING", null);
    }
    public static RevisionResponse from(RatingRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getRevisionNo(), "RATING",
                revision.getResolvedPath().name());
    }
}
