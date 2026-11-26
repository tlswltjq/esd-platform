package com.stove.settlement.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPaidEvent;
import com.stove.settlement.core.service.RecordPaidSale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class OrderEventListener {
    private final ObjectMapper mapper;
    private final RecordPaidSale recordPaidSale;

    @KafkaListener(topics = Topics.ORDER, groupId = RecordPaidSale.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.ORDER_PAID))
            recordPaidSale.execute(SettlementOrderMapper.from(envelope, envelope.payloadAs(mapper, OrderPaidEvent.class)));
    }
}
