package com.stove.catalog.api.application;

import com.stove.catalog.core.domain.Promotion;
import com.stove.catalog.core.port.CreatorWorkspacePort;
import com.stove.catalog.core.service.PromotionService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PromotionFacade {
    private final PromotionService promotions;
    private final CreatorWorkspacePort workspaces;

    public Promotion sellerCreate(Long productId, long discount, Instant start, Instant end,
                                  String actor, String token) {
        return promotions.create(productId, workspaces.ownWorkspace(token), Promotion.Bearer.SELLER,
                discount, start, end, actor);
    }

    public Promotion platformCreate(Long productId, long discount, Instant start, Instant end,
                                    String actor) {
        return promotions.create(productId, null, Promotion.Bearer.PLATFORM,
                discount, start, end, actor);
    }

    public Promotion sellerStop(Long id, String actor, String token) {
        return promotions.stop(id, workspaces.ownWorkspace(token), Promotion.Bearer.SELLER, actor);
    }

    public Promotion platformStop(Long id, String actor) {
        return promotions.stop(id, null, Promotion.Bearer.PLATFORM, actor);
    }

    public List<Promotion> sellerList(Long productId, String actor, String token) {
        return promotions.list(productId, workspaces.ownWorkspace(token), false, actor);
    }

    public List<Promotion> platformList(Long productId, String actor) {
        return promotions.list(productId, null, true, actor);
    }
}
