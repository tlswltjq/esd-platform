package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ApproveCaseRequest(
        @Size(max = 10) String ratingCode,
        @Size(max = 100) String certificationNumber,
        @Size(max = 100) String issuer,
        Instant issuedAt,
        @Size(max = 2) String country
) {
}
