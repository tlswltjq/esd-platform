package com.stove.license.api.listener;

import static com.stove.license.LicenseFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalCorrectionMapperTest {
    private final LicenseApprovalCorrectionRequestedEvent event=new LicenseApprovalCorrectionRequestedEvent(id(),Instant.parse("2026-10-29T01:00:00.123456Z"),id(),id(),id(),id(),id(),"buyer",1L,"PRODUCT-1","payment-1",id(),id());
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void validatesAndRetainsThePurchaseAndCorrectionIdentities() {
        assertThat(ApprovalCorrectionMapper.from(message(),event).stopRequestId()).isEqualTo(event.stopRequestId());
        assertThat(ApprovalCorrectionMapper.from(message(),event).suspensionEventId()).isEqualTo(event.suspensionEventId());
    }
    @Test void aMessageWithAnotherIdentityTypeOrOrderCannotAuthorizeRestoration() {
        for (EventMessage invalid:List.of(new EventMessage(id(),event.eventType(),event.orderNo(),""),
                new EventMessage(event.eventId(),"Other",event.orderNo(),""),new EventMessage(event.eventId(),event.eventType(),id(),"")))
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(invalid,event)).isInstanceOf(BusinessException.class);
    }
    @Test void missingPurchaseOrCorrectionEvidenceAndInvalidValuesAreRejectedAtTheBoundary() throws Exception {
        for (String field:List.of("eventId","occurredAt","refundId","reviewId","stopRequestId","licenseId","orderNo",
                "buyerSubject","productId","productCode","paymentId","suspensionRequestEventId","suspensionEventId")) {
            ObjectNode json=mapper.valueToTree(event); json.putNull(field); LicenseApprovalCorrectionRequestedEvent invalid=mapper.treeToValue(json,LicenseApprovalCorrectionRequestedEvent.class);
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(message(),invalid)).as(field).isInstanceOf(BusinessException.class);
        }
        for (String field:List.of("refundId","reviewId","stopRequestId","licenseId","orderNo")) {
            ObjectNode json=mapper.valueToTree(event); json.put(field,"bad-id"); LicenseApprovalCorrectionRequestedEvent invalid=mapper.treeToValue(json,LicenseApprovalCorrectionRequestedEvent.class);
            assertThatThrownBy(()->ApprovalCorrectionMapper.from(message(),invalid)).as(field).isInstanceOf(BusinessException.class);
        }
    }
    private EventMessage message() { return new EventMessage(event.eventId(),event.eventType(),event.orderNo(),""); }
}
