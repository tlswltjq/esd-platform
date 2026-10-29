package com.stove.payment.core.domain;

import java.time.Instant;
import org.springframework.data.domain.Page;

public record PaymentFailureHistory(Page<FailedPaymentAttempt> attempts, Instant checkedAt, boolean collectionPending) { }
