package com.stove.payment.api.listener;

import static com.stove.payment.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.service.ReceiveOrderPayment;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderEventListenerTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ReceiveOrderPayment receive=mock(ReceiveOrderPayment.class);
    private final OrderEventListener listener=new OrderEventListener(json,receive);
    private ConsumerRecord<String,String> record(OrderPlacedEvent event,String headerId,String type,String key,String body) {
        ConsumerRecord<String, String> r=new ConsumerRecord<String,String>(Topics.ORDER,0,0,key,body);
        r.headers().add(EventHeaders.EVENT_ID,headerId.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_TYPE,type.getBytes(StandardCharsets.UTF_8));return r;
    }
    @Test void delegatesOnlyValidatedInternalFacts() throws Exception {
        PaymentOrderRequest input=request(); OrderPlacedEvent event=event(input);
        listener.receive(record(event,event.eventId(),event.eventType(),event.orderNo(),json.writeValueAsString(event)));
        verify(receive).execute(input);
    }
    @Test void mismatchedEnvelopeCannotReachTheUseCase() throws Exception {
        OrderPlacedEvent e=event(request()); String body=json.writeValueAsString(e);
        for (EventEnvelope envelope:new EventEnvelope[]{null,new EventEnvelope("other",e.eventType(),e.orderNo(),body),
                new EventEnvelope(e.eventId(),"other",e.orderNo(),body),new EventEnvelope(e.eventId(),e.eventType(),"wrong",body)})
            assertThatThrownBy(() -> OrderPaymentMapper.from(envelope,e)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> listener.receive(record(e,"other",e.eventType(),e.orderNo(),body))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(receive);
    }
    @ParameterizedTest @ValueSource(strings={"amount","listAmount","discountAmount","productId","contentVersion"})
    void fractionalNumbersCannotBeTruncatedIntoAValidCharge(String field) {
        OrderPlacedEvent e=event(request()); JsonNode body=json.valueToTree(e); ((com.fasterxml.jackson.databind.node.ObjectNode)body).put(field,10000.5);
        assertThatThrownBy(() -> listener.receive(record(e,e.eventId(),e.eventType(),e.orderNo(),body.toString())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(receive);
    }
    @Test void ignoresLegacyContractsAndRejectsMissingHeaders() {
        OrderPlacedEvent e=event(request());listener.receive(record(e,e.eventId(),"OrderCreated",e.orderNo(),"{}"));
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.ORDER,0,0,e.orderNo(),"{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(receive);
    }
}
