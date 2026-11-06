package com.stove.order.core.service;

import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.ProductPublicAccess;
import com.stove.order.core.port.CatalogProducts;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
                        requirePurchasable(product);
                        Order order = orders.saveAndFlush(Order.create(buyer, request, product, UUID.randomUUID().toString()));
                        OrderPlacedEvent event = new OrderPlacedEvent(order.getRequestEventId(), order.getCreatedAt(),
                                order.getOrderNo(), buyer, order.getProductId(), order.getProductCode(),
                                order.getTitle(), order.getContentVersion(), order.getListAmount(),
                                order.getDiscountAmount(), order.getAmount(), order.getCurrency());
                        outbox.record("PurchaseOrder", order.getOrderNo(), event);
                        return order;
                    });
                } catch (DataIntegrityViolationException | TransientDataAccessException
                         | ConstraintViolationException
                         | LockAcquisitionException collision) {
                    Order concurrent = existing(buyer, request);
                    if (concurrent != null) return concurrent;
                    if (!(collision instanceof TransientDataAccessException)
                            && !(collision instanceof LockAcquisitionException)) throw collision;
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

    /** 결정 89: 구매를 막는 업무 사유는 판매 금지뿐이다. 중복 청구는 같은 requestId 멱등이 막고, 보유 중복은 License 등록이 막는다. */
    private void requirePurchasable(PublishedProduct product) {
        ProductPublicAccess.requireNewOrder(product.publicDetailAllowed());
    }
}
