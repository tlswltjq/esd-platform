package com.stove.studio.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildWebhookSubscriptionRepository extends JpaRepository<BuildWebhookSubscription, Long> {
    List<BuildWebhookSubscription> findByGameIdOrderByIdDesc(Long gameId);
    List<BuildWebhookSubscription> findByGameIdAndActiveTrue(Long gameId);
}
