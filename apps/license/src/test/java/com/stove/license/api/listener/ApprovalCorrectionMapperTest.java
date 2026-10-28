package com.stove.license.api.listener;

import static com.stove.license.LicenseFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalCorrectionMapperTest {
    private final LicenseApprovalCorrectionRequestedEvent event=new LicenseApprovalCorrectionRequestedEvent(id(),Instant.parse("2026-10-29T01:00:00.123456Z"),id(),id(),id(),id(),id(),"buyer",1L,"PRODUCT-1","payment-1",id(),id());
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void validatesAndRetainsThePurchaseAndCorrectionIdentities() {
        assertThat(ApprovalCorrectionMapper.from(envelope(),event).stopRequestId()).isEqualTo(event.stopRequestId());
        assertThat(ApprovalCorrectionMapper.from(envelope(),event).suspensionEventId()).isEqualTo(event.suspensionEventId());
    }
    @Test void anEnvelopeWithAnotherIdentityTypeOrOrderCannotAuthorizeRestoration() {
        for (EventEnvelope invalid:List.of(new EventEnvelope(id(),event.eventType(),event.orderNo(),""),
                new EventEnvelope(event.eventId(),"Other",event.orderNo(),""),new EventEnvelope(event.eventId(),event.eventType(),id(),"")))
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(invalid,event)).isInstanceOf(BusinessException.class);
    }
    @Test void missingPurchaseOrCorrectionEvidenceAndInvalidValuesAreRejectedAtTheBoundary() throws Exception {
        for (String field:List.of("eventId","occurredAt","refundId","reviewId","stopRequestId","licenseId","orderNo",
                "buyerSubject","productId","productCode","paymentId","suspensionRequestEventId","suspensionEventId")) {
            ObjectNode json=mapper.valueToTree(event); json.putNull(field); LicenseApprovalCorrectionRequestedEvent invalid=mapper.treeToValue(json,LicenseApprovalCorrectionRequestedEvent.class);
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(envelope(),invalid)).as(field).isInstanceOf(BusinessException.class);
        }
        for (String field:List.of("refundId","reviewId","stopRequestId","licenseId","orderNo")) {
            ObjectNode json=mapper.valueToTree(event); json.put(field,"bad-id"); LicenseApprovalCorrectionRequestedEvent invalid=mapper.treeToValue(json,LicenseApprovalCorrectionRequestedEvent.class);
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(envelope(),invalid)).as(field).isInstanceOf(BusinessException.class);
        }
    }
    private EventEnvelope envelope() { return new EventEnvelope(event.eventId(),event.eventType(),event.orderNo(),""); }
}
