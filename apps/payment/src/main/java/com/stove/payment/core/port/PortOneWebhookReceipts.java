package com.stove.payment.core.port;

import com.stove.payment.core.domain.PortOneWebhookNotice;
import java.time.Instant;
import java.util.Optional;

public interface PortOneWebhookReceipts {
    Optional<String> findPayloadHash(String messageId);
    void save(PortOneWebhookNotice notice, Instant receivedAt);
}
