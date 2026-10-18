package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import com.stove.order.core.port.CatalogProducts;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

@Service
public class CreateOrder {
    private final OrderRepository orders;
    private final CatalogProducts catalog;
    private final OutboxRecorder outbox;
    private final TransactionTemplate transaction;

    public CreateOrder(OrderRepository orders, CatalogProducts catalog, OutboxRecorder outbox,
                       PlatformTransactionManager transactions) {
        this.orders = orders; this.catalog = catalog; this.outbox = outbox;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Order execute(String buyer, OrderRequest request) {
        if (buyer == null || buyer.isBlank() || buyer.length() > 100 || request == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Order previous = existing(buyer, request);
        if (previous != null) return previous;
        try {
            // No Order transaction or lock is held during this HTTP call.
            PublishedProduct product = catalog.detail(request.productCode());
            if (product == null || !request.productCode().equals(product.productCode()))
                throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
            for (int attempt = 0; ; attempt++) {
                try {
                    return transaction.execute(tx -> {
                        Order concurrent = existing(buyer, request);
                        if (concurrent != null) return concurrent;
                        requirePurchasable(buyer, product);
                        Order order = orders.saveAndFlush(Order.create(buyer, request, product, UUID.randomUUID().toString()));
                        OrderPlacedEvent event = new OrderPlacedEvent(order.getRequestEventId(), order.getCreatedAt(),
                                order.getOrderNo(), buyer, order.getProductId(), order.getProductCode(),
                                order.getTitle(), order.getContentVersion(), order.getListAmount(),
                                order.getDiscountAmount(), order.getAmount(), order.getCurrency());
                        outbox.record("PurchaseOrder", order.getOrderNo(), event);
                        return order;
                    });
                } catch (DataIntegrityViolationException | TransientDataAccessException
                         | org.hibernate.exception.ConstraintViolationException
                         | org.hibernate.exception.LockAcquisitionException collision) {
                    Order concurrent = existing(buyer, request);
                    if (concurrent != null) return concurrent;
                    if (purchaseCollision(collision)) requirePurchasable(buyer, product);
                    else if (!(collision instanceof TransientDataAccessException)
                            && !(collision instanceof org.hibernate.exception.LockAcquisitionException)) throw collision;
                    if (attempt == 2) throw collision;
                }
            }
        } catch (RuntimeException failure) {
            // A competing request may have committed while Catalog failed or changed.
            Order concurrent = existing(buyer, request);
            if (concurrent != null) return concurrent;
            throw failure;
        }
    }

    private Order existing(String buyer, OrderRequest request) {
        Order order = orders.findByBuyerSubjectAndRequestId(buyer, request.requestId()).orElse(null);
        if (order != null) order.requireSameRequest(request);
        return order;
    }

    private void requirePurchasable(String buyer, PublishedProduct product) {
        List<Order> conflicts = orders.findPurchaseConflicts(buyer, product.productId(), product.productCode(),
                List.of(OrderStatus.PENDING, OrderStatus.PAID));
        if (conflicts.isEmpty()) return;
        if (conflicts.size() != 1) throw new BusinessException(ErrorCode.CONFLICT, "구매 제한 주문의 상품 연결이 다릅니다.");
        Order previous = conflicts.getFirst();
        if (!previous.getProductId().equals(product.productId()) || !previous.getProductCode().equals(product.productCode()))
            throw new BusinessException(ErrorCode.CONFLICT, "기존 주문의 상품 ID와 코드가 일치하지 않습니다.");
        throw new BusinessException(previous.getStatus() == OrderStatus.PAID
                ? OrderErrorCode.PRODUCT_ALREADY_PURCHASED : OrderErrorCode.PURCHASE_IN_PROGRESS);
    }

    private static boolean purchaseCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                return name != null && (name.contains("uk_purchase_order_buyer_product")
                        || name.contains("uk_purchase_order_buyer_code"));
            }
        }
        return false;
    }
}
