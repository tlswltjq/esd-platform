package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.PaymentAdmissionInput;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdmitOrderPaymentTest {
    @Test void aPlatformRestrictionRejectsANewAdmissionWithoutChangingTheExistingOrder() {
        Scenario s=new Scenario(); s.catalog("BLOCKED");
        assertThatThrownBy(() -> s.service.execute(s.input)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(OrderErrorCode.PRODUCT_SALE_RESTRICTED));
        verifyNoInteractions(s.processing);
        assertThat(s.order.getPaymentId()).isNull();
    }
    @Test void sellerStopAndChangedCurrentPricesDoNotChangeOriginalAdmissionTerms() {
        Scenario s=new Scenario(); s.catalog("STOPPED");
        s.service.execute(s.input); verify(s.processing).execute(s.input);
    }
    @Test void anAlreadyRecordedAdmissionIsReturnedWithoutRecheckingLaterRestrictions() {
        Scenario s=new Scenario();
        when(s.admissions.findByAdmissionId(s.input.admissionId())).thenReturn(Optional.of(mock(OrderPaymentAdmission.class)));
        s.service.execute(s.input);
        verifyNoInteractions(s.catalog); verify(s.processing).execute(s.input);
    }
    @Test void missingOrDifferentProductEvidenceCannotAdmitThePurchase() {
        Scenario s=new Scenario();
        when(s.catalog.detail(s.order.getProductCode())).thenReturn(new PublishedProduct(999L,"Game-1","Changed",3,10000,"KRW","ON_SALE"));
        assertThatThrownBy(() -> s.service.execute(s.input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(s.processing);
    }
    private static class Scenario {
        final Order order=order();
        final OrderRepository orders=mock(OrderRepository.class);
        final OrderPaymentAdmissionRepository admissions=mock(OrderPaymentAdmissionRepository.class);
        final CatalogProducts catalog=mock(CatalogProducts.class);
        final RequestOrderPaymentProcessing processing=mock(RequestOrderPaymentProcessing.class);
        final PaymentAdmissionInput input=new PaymentAdmissionInput(id(),order.getOrderNo(),order.getRequestEventId(),id(),
                order.getBuyerSubject(),order.getAmount(),order.getCurrency());
        final AdmitOrderPayment service=new AdmitOrderPayment(orders,admissions,catalog,processing,
                Clock.fixed(order.getCreatedAt().plusSeconds(1),ZoneOffset.UTC));
        Scenario() {
            when(orders.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
            when(admissions.findByAdmissionId(input.admissionId())).thenReturn(Optional.empty());
        }
        void catalog(String status) {
            when(catalog.detail(order.getProductCode())).thenReturn(new PublishedProduct(order.getProductId(),order.getProductCode(),
                    "Current title",order.getContentVersion()+1,order.getAmount()+10000,order.getCurrency(),status));
        }
    }
}
