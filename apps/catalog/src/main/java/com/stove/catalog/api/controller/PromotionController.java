package com.stove.catalog.api.controller;

import com.stove.catalog.core.domain.Promotion;
import com.stove.catalog.api.application.PromotionFacade;
import com.stove.common.core.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequiredArgsConstructor
@RequestMapping("/api/v1/promotions")
public class PromotionController {
    private final PromotionFacade promotions;

    public record CreateRequest(@Min(1) long discountPerUnit,
                                @NotNull Instant startsAt, @NotNull Instant endsAt) {}

    public record Response(Long id, Long productId, Long sellerId, long discountPerUnit,
                           Promotion.Bearer bearer, Instant startsAt, Instant endsAt, Instant stoppedAt) {
        static Response from(Promotion p) {
            return new Response(p.getId(), p.getProductId(), p.getSellerId(), p.getDiscountPerUnit(),
                    p.getBearer(), p.getStartsAt(), p.getEndsAt(), p.getStoppedAt());
        }
    }

    @PostMapping("/seller/products/{productId}")
    public ApiResponse<Response> sellerCreate(@PathVariable Long productId,
            @Valid @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(Response.from(promotions.sellerCreate(productId,
                request.discountPerUnit(), request.startsAt(), request.endsAt(),
                jwt.getSubject(), jwt.getTokenValue())));
    }

    @PostMapping("/platform/products/{productId}")
    public ApiResponse<Response> platformCreate(@PathVariable Long productId,
            @Valid @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(Response.from(promotions.platformCreate(productId,
                request.discountPerUnit(), request.startsAt(), request.endsAt(), jwt.getSubject())));
    }

    @PostMapping("/seller/{id}/stop")
    public ApiResponse<Response> sellerStop(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(Response.from(promotions.sellerStop(id, jwt.getSubject(), jwt.getTokenValue())));
    }

    @PostMapping("/platform/{id}/stop")
    public ApiResponse<Response> platformStop(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(Response.from(promotions.platformStop(id, jwt.getSubject())));
    }

    @GetMapping("/seller/products/{productId}")
    public ApiResponse<List<Response>> sellerList(@PathVariable Long productId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(promotions.sellerList(productId, jwt.getSubject(), jwt.getTokenValue())
                .stream().map(Response::from).toList());
    }

    @GetMapping("/platform/products/{productId}")
    public ApiResponse<List<Response>> platformList(@PathVariable Long productId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(promotions.platformList(productId, jwt.getSubject())
                .stream().map(Response::from).toList());
    }
}
