package com.stove.payment.adapter.pg;

import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcPortOneWebhookReceipts implements PortOneWebhookReceipts {
    private final JdbcTemplate jdbc;

    public JdbcPortOneWebhookReceipts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<String> findPayloadHash(String messageId) {
        List<String> hashes = jdbc.query("SELECT payload_hash FROM payment_pg_webhook WHERE message_id=?",
                (row, index) -> row.getString(1), messageId);
        return hashes.isEmpty() ? Optional.empty() : Optional.of(hashes.getFirst());
    }

    @Override
    public void save(PortOneWebhookNotice notice, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO payment_pg_webhook
                (message_id,payload_hash,event_type,store_id,payment_id,transaction_id,occurred_at,received_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, notice.messageId(), notice.payloadHash(), notice.type(), notice.storeId(), notice.paymentId(),
                notice.transactionId(), Timestamp.from(notice.occurredAt()), Timestamp.from(receivedAt));
    }
}
