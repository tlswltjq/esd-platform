package com.stove.common.messaging.quarantine;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QuarantinedMessageRepository extends JpaRepository<QuarantinedMessage, Long> {

    boolean existsByConsumerGroupAndTopicAndPartitionKeyAndStatus(
            String consumerGroup, String topic, String partitionKey, QuarantineStatus status);

    /** 그 키의 보관 메시지를 원래 순서(id)로. */
    List<QuarantinedMessage> findByConsumerGroupAndTopicAndPartitionKeyAndStatusOrderByIdAsc(
            String consumerGroup, String topic, String partitionKey, QuarantineStatus status);

    /** 멈춘 키 목록: 그룹·토픽·키·보관 건수·첫 보관 id. */
    @Query("SELECT new com.stove.common.messaging.quarantine.StoppedKey(m.consumerGroup, m.topic, m.partitionKey,"
            + " COUNT(m), MIN(m.id), MIN(m.quarantinedAt)) FROM QuarantinedMessage m WHERE m.status = :status"
            + " GROUP BY m.consumerGroup, m.topic, m.partitionKey ORDER BY MIN(m.id)")
    List<StoppedKey> findStoppedKeys(QuarantineStatus status);

    long countByStatus(QuarantineStatus status);
}
