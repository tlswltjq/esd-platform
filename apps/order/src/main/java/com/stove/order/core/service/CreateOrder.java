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
        var previous = existing(buyer, request);
        if (previous != null) return previous;
        try {
            // No Order transaction or lock is held during this HTTP call.
            var product = catalog.detail(request.productCode());
            if (product == null || !request.productCode().equals(product.productCode()))
                throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
            for (int attempt = 0; ; attempt++) {
                try {
                    return transaction.execute(tx -> {
                        var concurrent = existing(buyer, request);
                        if (concurrent != null) return concurrent;
                        var order = orders.saveAndFlush(Order.create(buyer, request, product, UUID.randomUUID().toString()));
                        var event = new OrderPlacedEvent(order.getRequestEventId(), order.getCreatedAt(),
                                order.getOrderNo(), buyer, order.getProductId(), order.getProductCode(),
                                order.getTitle(), order.getContentVersion(), order.getListAmount(),
                                order.getDiscountAmount(), order.getAmount(), order.getCurrency());
                        outbox.record("PurchaseOrder", order.getOrderNo(), event);
                        return order;
                    });
                } catch (DataIntegrityViolationException | TransientDataAccessException
                         | org.hibernate.exception.ConstraintViolationException
                         | org.hibernate.exception.LockAcquisitionException collision) {
                    var concurrent = existing(buyer, request);
                    if (concurrent != null) return concurrent;
                    if (attempt == 2) throw collision;
                }
            }
        } catch (RuntimeException failure) {
            // A competing request may have committed while Catalog failed or changed.
            var concurrent = existing(buyer, request);
            if (concurrent != null) return concurrent;
            throw failure;
        }
    }

    private Order existing(String buyer, OrderRequest request) {
        var order = orders.findByBuyerSubjectAndRequestId(buyer, request.requestId()).orElse(null);
        if (order != null) order.requireSameRequest(request);
        return order;
    }
}
