package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.order.api.controller.dto.AdminRefundResponse;
import com.stove.order.api.controller.dto.RefundResponse;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 결정 125: 게이지 집계, 고객 processingNotice, ADMIN 진입 시각. */
class ReadLicenseGrantDelaysTest {
    private static final Instant BASE = Instant.parse("2026-11-08T10:30:00.123456Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(10);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final RefundReviewRoundRepository rounds = mock(RefundReviewRoundRepository.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void theGaugeCountsWaitingRefundsWhoseStartIsAtOrBeforeNowMinusTheThreshold() {
        Instant now = BASE.plusSeconds(3600);
        when(refunds.countByStatusAndLicenseGrantWaitStartedAtLessThanEqual(RefundStatus.PENDING_LICENSE_GRANT, now.minus(THRESHOLD))).thenReturn(3L);
        assertThat(new ReadLicenseGrantDelays(refunds, Clock.fixed(now, ZoneOffset.UTC), THRESHOLD).count()).isEqualTo(3L);
    }

    @Test void eachWaitStatusIsCountedFromItsOwnEntryTimeWithTheSameBoundary() {
        Instant now = BASE.plusSeconds(3600);
        Instant cutoff = now.minus(THRESHOLD);
        when(refunds.countByStatusAndLicenseGrantWaitStartedAtLessThanEqual(RefundStatus.PENDING_LICENSE_GRANT, cutoff)).thenReturn(1L);
        when(refunds.countByStatusAndLicenseSuspensionWaitStartedAtLessThanEqual(RefundStatus.PENDING_LICENSE_SUSPENSION, cutoff)).thenReturn(2L);
        when(refunds.countByStatusAndLicenseRestorationWaitStartedAtLessThanEqual(RefundStatus.PENDING_LICENSE_RESTORATION, cutoff)).thenReturn(3L);
        when(refunds.countByStatusAndApprovalStopWaitStartedAtLessThanEqual(RefundStatus.PENDING_APPROVAL_STOP, cutoff)).thenReturn(4L);
        ReadLicenseGrantDelays delays = new ReadLicenseGrantDelays(refunds, Clock.fixed(now, ZoneOffset.UTC), THRESHOLD);
        assertThat(ReadLicenseGrantDelays.STATUSES).containsExactly(RefundStatus.PENDING_LICENSE_GRANT, RefundStatus.PENDING_LICENSE_SUSPENSION,
                RefundStatus.PENDING_LICENSE_RESTORATION, RefundStatus.PENDING_APPROVAL_STOP);
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_GRANT)).isEqualTo(1L);
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_SUSPENSION)).isEqualTo(2L);
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_RESTORATION)).isEqualTo(3L);
        assertThat(delays.count(RefundStatus.PENDING_APPROVAL_STOP)).isEqualTo(4L);
        assertThatThrownBy(() -> delays.count(RefundStatus.PENDING_PAYMENT_REFUND)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void theCustomerSeesAStateSpecificNoticeFromEachWaitBoundary() throws Exception {
        assertNotice(RefundStatus.PENDING_LICENSE_SUSPENSION, "licenseSuspensionWaitStartedAt", "LICENSE_SUSPENSION_CONFIRMATION_DELAYED",
                "환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.");
        assertNotice(RefundStatus.PENDING_APPROVAL_STOP, "approvalStopWaitStartedAt", "APPROVAL_STOP_CONFIRMATION_DELAYED",
                "환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.");
        assertNotice(RefundStatus.PENDING_LICENSE_RESTORATION, "licenseRestorationWaitStartedAt", "LICENSE_RESTORATION_CONFIRMATION_DELAYED",
                "결제 취소가 처리되지 않아 이용권을 되돌리고 있으나 지연되고 있습니다. 결제 금액은 환불되지 않았으며, 이용권이 돌아오면 다시 신청할 수 있습니다.");
    }

    @Test void theAdminDetailShowsEachWaitStart() {
        Fixture f = waiting();
        ReflectionTestUtils.setField(f.refund(), "licenseSuspensionWaitStartedAt", BASE.plusSeconds(1));
        ReflectionTestUtils.setField(f.refund(), "licenseRestorationWaitStartedAt", BASE.plusSeconds(2));
        ReflectionTestUtils.setField(f.refund(), "approvalStopWaitStartedAt", BASE.plusSeconds(3));
        when(refunds.findByRefundId(f.refund().getRefundId())).thenReturn(Optional.of(f.refund()));
        when(orders.findByOrderNo(f.order().getOrderNo())).thenReturn(Optional.of(f.order()));
        AdminRefundResponse admin = AdminRefundResponse.from(new ReadRefundReviews(orders, refunds, rounds).read(f.refund().getRefundId()));
        assertThat(admin.licenseGrantWaitStartedAt()).isEqualTo(BASE);
        assertThat(admin.licenseSuspensionWaitStartedAt()).isEqualTo(BASE.plusSeconds(1));
        assertThat(admin.licenseRestorationWaitStartedAt()).isEqualTo(BASE.plusSeconds(2));
        assertThat(admin.approvalStopWaitStartedAt()).isEqualTo(BASE.plusSeconds(3));
    }

    private void assertNotice(RefundStatus status, String field, String code, String message) throws Exception {
        Fixture f = waiting();
        ReflectionTestUtils.setField(f.refund(), "status", status);
        ReflectionTestUtils.setField(f.refund(), field, BASE);
        Instant boundary = BASE.plus(THRESHOLD);
        assertThat(customer(f, boundary.minusNanos(1000)).path("processingNotice").isNull()).as(status.name()).isTrue();
        JsonNode body = customer(f, boundary);
        assertThat(body.path("status").asText()).isEqualTo("PROCESSING");
        JsonNode notice = body.path("processingNotice");
        assertThat(notice.size()).isEqualTo(3);
        assertThat(notice.path("code").asText()).isEqualTo(code);
        assertThat(notice.path("since").asText()).isEqualTo(boundary.toString());
        assertThat(notice.path("message").asText()).isEqualTo(message);
    }

    @Test void aMissingOrNonPositiveThresholdIsRejectedAtAssembly() {
        assertThatThrownBy(() -> new ReadLicenseGrantDelays(refunds, Clock.systemUTC(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadLicenseGrantDelays(refunds, Clock.systemUTC(), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadRefund(orders, refunds, rounds, Clock.systemUTC(), mock(RefundAttemptRepository.class), Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void theCustomerSeesTheGrantDelayNoticeOnlyFromTheBoundaryWhileStatusStaysProcessing() throws Exception {
        Fixture f = waiting();
        Instant boundary = BASE.plus(THRESHOLD);
        assertThat(customer(f, boundary.minusNanos(1000)).path("processingNotice").isNull()).isTrue();
        JsonNode body = customer(f, boundary);
        assertThat(body.path("status").asText()).isEqualTo("PROCESSING");
        JsonNode notice = body.path("processingNotice");
        assertThat(notice.size()).isEqualTo(3);
        assertThat(notice.path("code").asText()).isEqualTo("LICENSE_GRANT_CONFIRMATION_DELAYED");
        assertThat(notice.path("since").asText()).isEqualTo(boundary.toString());
        assertThat(notice.path("message").asText()).isEqualTo("환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.");
    }

    @Test void otherStatesDoNotGetTheGrantDelayNoticeEvenWithAnOldStart() throws Exception {
        Fixture f = waiting();
        ReflectionTestUtils.setField(f.refund(), "status", RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(customer(f, BASE.plusSeconds(86400)).path("processingNotice").isNull()).isTrue();
    }

    @Test void theAdminDetailShowsTheWaitStart() {
        Fixture f = waiting();
        when(refunds.findByRefundId(f.refund().getRefundId())).thenReturn(Optional.of(f.refund()));
        when(orders.findByOrderNo(f.order().getOrderNo())).thenReturn(Optional.of(f.order()));
        AdminRefundResponse admin = AdminRefundResponse.from(new ReadRefundReviews(orders, refunds, rounds).read(f.refund().getRefundId()));
        assertThat(admin.licenseGrantWaitStartedAt()).isEqualTo(BASE);
    }

    private JsonNode customer(Fixture f, Instant at) throws Exception {
        when(orders.findByOrderNoAndBuyerSubject(f.order().getOrderNo(), f.order().getBuyerSubject())).thenReturn(Optional.of(f.order()));
        when(refunds.findByOrderNo(f.order().getOrderNo())).thenReturn(Optional.of(f.refund()));
        ReadRefund read = new ReadRefund(orders, refunds, rounds, Clock.fixed(at, ZoneOffset.UTC), mock(RefundAttemptRepository.class), THRESHOLD);
        return json.readTree(json.writeValueAsString(RefundResponse.from(read.execute(f.order().getOrderNo(), f.order().getBuyerSubject()))));
    }

    private Fixture waiting() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), BASE.minusSeconds(3600), paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return new Fixture(paid, Refund.request(paid, BASE));
    }

    private record Fixture(Order order, Refund refund) { }
}
