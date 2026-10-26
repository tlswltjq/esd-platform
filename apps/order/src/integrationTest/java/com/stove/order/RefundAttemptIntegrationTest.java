package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundAttemptStatus;
import com.stove.order.core.domain.RefundRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InfraContainers.MySql.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundAttemptIntegrationTest {
    private static final Instant PAYMENT_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAYMENT_AT.plusSeconds(3 * 3600);
    private static final Instant STARTED_AT = RECEIVED_AT.plusSeconds(10);
    private static final Instant RESOLVED_AT = STARTED_AT.plusSeconds(60);

    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach @AfterEach void clear() {
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void separateAttemptsPreserveTheFirstFailureAndTheOriginalRefundEligibility() {
        Refund refund = received();
        Order original = orders.findByOrderNoAndBuyerSubject(refund.getOrderNo(), "usr_buyer").orElseThrow();
        PaymentResult purchase = original.paymentResult();
        RefundAttempt first = RefundAttempt.start(refund.getRefundId(), 1, STARTED_AT);
        first.fail(RESOLVED_AT, "DECLINED", "첫 실행이 확정 거절되었습니다.");
        attempts.saveAndFlush(first);
        // 이력 저장 능력을 검증하는 픽스처다. 재시도 허용이나 PG 실행을 수행하지 않는다.
        RefundAttempt second = RefundAttempt.start(refund.getRefundId(), 2, PAYMENT_AT.plusSeconds(6 * 3600));
        attempts.saveAndFlush(second);

        List<RefundAttempt> history = attempts.findByRefundIdOrderByAttemptNoAsc(refund.getRefundId());

        assertThat(history).extracting(RefundAttempt::getAttemptId).containsExactly(first.getAttemptId(), second.getAttemptId());
        assertThat(history).extracting(RefundAttempt::getAttemptNo).containsExactly(1, 2);
        assertThat(history.get(0).getStatus()).isEqualTo(RefundAttemptStatus.FAILED);
        assertThat(history.get(0).getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(history.get(0).getResolvedAt()).isEqualTo(RESOLVED_AT);
        assertThat(history.get(0).getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(history.get(0).getFailureReason()).isEqualTo("첫 실행이 확정 거절되었습니다.");
        assertThat(history.get(1).getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(history.get(1).getResolvedAt()).isNull();
        Refund stored = refunds.findByOrderNo(refund.getOrderNo()).orElseThrow();
        assertThat(refunds.count()).isEqualTo(1);
        assertThat(stored.getRefundId()).isEqualTo(refund.getRefundId());
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.isAutomaticRefundEligible()).isTrue();
        assertThat(stored.getStatus()).isEqualTo(refund.getStatus());
        assertThat(orders.findById(original.getId()).orElseThrow().paymentResult()).isEqualTo(purchase);
    }

    @ParameterizedTest @ValueSource(strings = {"number", "identity"})
    void theDatabaseRejectsReusedAttemptNumbersOrIdentities(String collision) {
        Refund refund = received();
        RefundAttempt first = attempts.saveAndFlush(RefundAttempt.start(refund.getRefundId(), 1, STARTED_AT));
        RefundAttempt duplicate = RefundAttempt.start(refund.getRefundId(), collision.equals("number") ? 1 : 2, STARTED_AT);
        if (collision.equals("identity")) ReflectionTestUtils.setField(duplicate, "attemptId", first.getAttemptId());

        assertThatThrownBy(() -> attempts.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts.count()).isEqualTo(1);
        assertThat(attempts.findByAttemptId(first.getAttemptId()).orElseThrow().getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
    }

    @Test void anAttemptMustReferToAnExistingRefund() {
        RefundAttempt orphan = RefundAttempt.start(id(), 1, STARTED_AT);

        assertThatThrownBy(() -> attempts.saveAndFlush(orphan)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts.count()).isZero();
        assertThat(refunds.count()).isZero();
    }

    @Test void abortedChangesDoNotLeaveAPartialResultOrANewHistoryEntry() {
        Refund refund = received();
        RefundAttempt first = attempts.saveAndFlush(RefundAttempt.start(refund.getRefundId(), 1, STARTED_AT));

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            RefundAttempt current = attempts.findByAttemptId(first.getAttemptId()).orElseThrow();
            current.fail(RESOLVED_AT, "DECLINED", "거절");
            attempts.saveAndFlush(RefundAttempt.start(refund.getRefundId(), 2, RESOLVED_AT.plusSeconds(1)));
            throw new IllegalStateException("abort history changes");
        })).isInstanceOf(IllegalStateException.class).hasMessage("abort history changes");

        RefundAttempt stored = attempts.findByAttemptId(first.getAttemptId()).orElseThrow();
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(stored.getResolvedAt()).isNull();
        assertThat(stored.getFailureReasonCode()).isNull();
        assertThat(stored.getFailureReason()).isNull();
        assertThat(refunds.findByOrderNo(refund.getOrderNo()).orElseThrow().getReceivedAt()).isEqualTo(RECEIVED_AT);
    }

    private Refund received() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAYMENT_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        orders.saveAndFlush(paid);
        return refunds.saveAndFlush(Refund.request(paid, RECEIVED_AT));
    }
}
