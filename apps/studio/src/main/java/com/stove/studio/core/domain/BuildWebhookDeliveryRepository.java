package com.stove.studio.core.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildWebhookDeliveryRepository extends JpaRepository<BuildWebhookDelivery, Long> {
    List<BuildWebhookDelivery> findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
            Collection<WebhookDeliveryStatus> statuses, Instant now);
}
