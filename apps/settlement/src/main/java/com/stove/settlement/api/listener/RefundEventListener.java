package com.stove.settlement.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RefundEventListener {
    private final ObjectMapper mapper;
    private final RecordConfirmedRefund refunds;
    @KafkaListener(topics = Topics.PAYMENT, groupId = RecordConfirmedRefund.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.REFUND_PAYMENT_CONFIRMED))
            refunds.execute(SettlementRefundMapper.from(envelope, envelope.payloadAs(mapper, RefundPaymentConfirmedEvent.class)));
    }
}
