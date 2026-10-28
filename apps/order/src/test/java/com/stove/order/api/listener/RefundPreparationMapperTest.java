package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundPreparation;
import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class RefundPreparationMapperTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final Instant first=Instant.parse("2026-10-29T01:00:00.123456Z");
    private final RefundExecutionPreparedEvent event=new RefundExecutionPreparedEvent(id(),first,id(),id(),id(),id(),"payment-1","buyer",9000,"KRW",id(),null,first,first.plusSeconds(10800));
    @Test void preservesEveryPurchaseRequestAndPreparationField() {
        RefundPreparationConfirmation expected=new RefundPreparationConfirmation(event.eventId(),first,event.refundId(),event.attemptId(),event.requestEventId(),event.orderNo(),
                event.paymentId(),event.buyerSubject(),event.amount(),event.currency(),event.licenseSuspensionEventId(),null,first,event.confirmationDelayAt());
        assertThat(RefundPreparationMapper.from(envelope(),event)).isEqualTo(expected);
    }
    @Test void wrongEnvelopeIdentityTypeOrKeyCannotSupplyProgress() {
        for (EventEnvelope bad:List.of(new EventEnvelope(id(),event.eventType(),event.orderNo(),""),new EventEnvelope(event.eventId(),"Other",event.orderNo(),""),new EventEnvelope(event.eventId(),event.eventType(),id(),"")))
            assertThatThrownBy(()->RefundPreparationMapper.from(bad,event)).isInstanceOf(RefundCompletionRejected.class);
    }
    @Test void missingEvidenceInvalidValuesAndAnotherDeadlineAreRejected() throws Exception {
        for (String field:List.of("eventId","occurredAt","refundId","attemptId","requestEventId","orderNo","paymentId","buyerSubject","amount","currency","licenseSuspensionEventId","firstPreparedAt","confirmationDelayAt")) {
            ObjectNode json=mapper.valueToTree(event);json.putNull(field);RefundExecutionPreparedEvent bad=mapper.treeToValue(json,RefundExecutionPreparedEvent.class);
            assertThatThrownBy(()->RefundPreparationMapper.from(envelope(),bad)).as(field).isInstanceOf(RefundCompletionRejected.class);
        }
        for (String field:List.of("refundId","attemptId","orderNo","previousAttemptId")) {
            ObjectNode json=mapper.valueToTree(event);json.put(field,"bad-id");RefundExecutionPreparedEvent bad=mapper.treeToValue(json,RefundExecutionPreparedEvent.class);
            assertThatThrownBy(()->RefundPreparationMapper.from(envelope(),bad)).as(field).isInstanceOf(RefundCompletionRejected.class);
        }
        for (String field:List.of("confirmationDelayAt","firstPreparedAt")) {
            ObjectNode json=mapper.valueToTree(event);json.put(field,first.plusSeconds(3600).toString());RefundExecutionPreparedEvent bad=mapper.treeToValue(json,RefundExecutionPreparedEvent.class);
            assertThatThrownBy(()->RefundPreparationMapper.from(envelope(),bad)).as(field).isInstanceOf(RefundCompletionRejected.class);
        }
    }
    @Test void wrongPublisherAndMalformedPayloadNeverReachTheService() throws Exception {
        ApplyRefundPreparation apply=mock(ApplyRefundPreparation.class);RefundPreparationEventListener listener=new RefundPreparationEventListener(mapper,apply);
        ConsumerRecord<String,String> wrong=record("stove.order.v1",mapper.writeValueAsString(event));
        assertThatThrownBy(()->listener.receive(wrong)).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(()->listener.receive(record(event.topic(),"{malformed"))).isInstanceOf(RefundCompletionRejected.class);verifyNoInteractions(apply);
    }
    private ConsumerRecord<String,String> record(String topic,String body) {
        ConsumerRecord<String,String> record=new ConsumerRecord<>(topic,0,0,event.orderNo(),body);record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8));record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));return record;
    }
    private EventEnvelope envelope() { return new EventEnvelope(event.eventId(),event.eventType(),event.orderNo(),""); }
}
