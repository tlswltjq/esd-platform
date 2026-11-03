package com.stove.review.core.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A stable lock target also exists before the original submission has arrived. */
@Entity
@Table(name = "review_request_gate")
public class ReviewRequestGate {
    @Id private Long requestId;
}
