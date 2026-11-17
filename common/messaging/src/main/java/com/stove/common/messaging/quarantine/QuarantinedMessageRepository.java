package com.stove.common.messaging.quarantine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QuarantinedMessageRepository extends JpaRepository<QuarantinedMessage, Long> {

    boolean existsByConsumerGroupAndTopicAndPartitionKeyAndStatusIn(
            String consumerGroup, String topic, String partitionKey, Collection<QuarantineStatus> statuses);

    long countByConsumerGroupAndTopicAndPartitionKeyAndStatusIn(
            String consumerGroup, String topic, String partitionKey, Collection<QuarantineStatus> statuses);

    /**
     * 그 키에서 아직 끝나지 않은 맨 앞 행을 잠근다({@code FOR UPDATE}). 같은 키의 재투입끼리 여기서 줄을 선다(PR #209 R2).
     * 한 번에 한 행만 읽어 오래 멈춘 키도 본문을 한꺼번에 올리지 않는다(R3).
     */
    @Query(value = "SELECT * FROM consumer_quarantine WHERE consumer_group = :consumerGroup AND topic = :topic"
            + " AND partition_key = :partitionKey AND status IN ('HELD', 'REPLAYING') ORDER BY id LIMIT 1 FOR UPDATE",
            nativeQuery = true)
    Optional<QuarantinedMessage> lockHead(String consumerGroup, String topic, String partitionKey);

    /** 멈춘 키 목록: 그룹·토픽·키·보관 건수·첫 보관 id·첫 보관 시각. */
    @Query("SELECT new com.stove.common.messaging.quarantine.StoppedKey(m.consumerGroup, m.topic, m.partitionKey,"
            + " COUNT(m), MIN(m.id), MIN(m.quarantinedAt)) FROM QuarantinedMessage m WHERE m.status IN :statuses"
            + " GROUP BY m.consumerGroup, m.topic, m.partitionKey ORDER BY MIN(m.id)")
    List<StoppedKey> findStoppedKeys(Collection<QuarantineStatus> statuses);

    long countByStatusIn(Collection<QuarantineStatus> statuses);

    List<QuarantinedMessage> findByConsumerGroupAndTopicAndPartitionKeyAndStatusOrderByIdAsc(
            String consumerGroup, String topic, String partitionKey, QuarantineStatus status);
}
