package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "purchase_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 36) private String orderNo;
    @Column(nullable = false, length = 100) private String buyerSubject;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false, length = 50) private String productCode;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false) private long contentVersion;
    @Column(nullable = false) private long listAmount;
    @Column(nullable = false) private long discountAmount;
    @Column(nullable = false) private long amount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrderStatus status;
    @Column(nullable = false, unique = true, length = 64) private String requestEventId;
    @Column(unique = true, length = 64) private String paymentResultEventId;
    @Column(length = 64) private String paymentId;
    private Instant resultOccurredAt;
    @Column(length = 100) private String failureReasonCode;
    @Column(length = 500) private String failureReason;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    public static Order create(String buyer, OrderRequest request, PublishedProduct product, String eventId) {
        if (buyer == null || buyer.isBlank() || buyer.length() > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "구매자 식별자가 올바르지 않습니다.");
        if (eventId == null || eventId.isBlank() || eventId.length() > 64)
            throw new IllegalArgumentException("주문 이벤트 ID가 필요합니다.");
        if (product.price() == 0)
            throw new BusinessException(ErrorCode.CONFLICT, "무료 상품 지급은 이번 주문 범위에 포함되지 않습니다.");
        if (!request.productCode().equals(product.productCode())
                || request.expectedContentVersion() != product.contentVersion()
                || request.expectedAmount() != product.price())
            throw new BusinessException(ErrorCode.CONFLICT, "공개 상품 버전 또는 금액이 변경되었습니다.");
        OrderPrice price = OrderPrice.atListPrice(product);
        Order order = new Order();
        order.orderNo = UUID.randomUUID().toString();
        order.buyerSubject = buyer; order.requestId = request.requestId();
        order.productId = product.productId(); order.productCode = product.productCode();
        order.title = product.title(); order.contentVersion = product.contentVersion();
        order.listAmount = price.listAmount(); order.discountAmount = price.discountAmount();
        order.amount = price.amount(); order.currency = price.currency();
        order.status = OrderStatus.PENDING; order.requestEventId = eventId;
        order.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); order.updatedAt = order.createdAt;
        return order;
    }

    public void requireSameRequest(OrderRequest request) {
        if (!requestId.equals(request.requestId()) || !productCode.equals(request.productCode())
                || contentVersion != request.expectedContentVersion() || amount != request.expectedAmount())
            throw new BusinessException(ErrorCode.CONFLICT, "동일 요청 ID의 주문 내용이 다릅니다.");
    }

    public PaymentResult paymentResult() {
        return paymentResultEventId == null ? null : new PaymentResult(paymentResultEventId, resultOccurredAt,
                orderNo, requestEventId, paymentId, buyerSubject, amount, currency, status,
                failureReasonCode, failureReason);
    }

    public void apply(PaymentResult result) {
        if (!orderNo.equals(result.orderNo()) || !requestEventId.equals(result.requestEventId())
                || !buyerSubject.equals(result.buyerSubject()) || amount != result.amount()
                || !currency.equals(result.currency()))
            throw new BusinessException(ErrorCode.CONFLICT, "결제 결과가 주문과 일치하지 않습니다.");
        if (paymentResultEventId != null) {
            if (!paymentResult().equals(result))
                throw new BusinessException(ErrorCode.CONFLICT, "이미 확정한 결제 결과와 다릅니다.");
            return;
        }
        if (result.status() != OrderStatus.PAID && result.status() != OrderStatus.FAILED)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "최종 결제 결과가 필요합니다.");
        paymentResultEventId = result.eventId(); paymentId = result.paymentId();
        resultOccurredAt = result.occurredAt(); status = result.status();
        failureReasonCode = result.reasonCode(); failureReason = result.reason();
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
