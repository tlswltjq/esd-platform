package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentAdmissionInput;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Checks the current platform restriction before the short, locked Order admission transaction. */
@Service @RequiredArgsConstructor
public class AdmitOrderPayment {
    private final OrderRepository orders;
    private final OrderPaymentAdmissionRepository admissions;
    private final CatalogProducts catalog;
    private final RequestOrderPaymentProcessing processing;
    private final Clock clock;

    public Optional<OrderPaymentAdmission> execute(PaymentAdmissionInput input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Catalog lookup requires no active Order transaction");
        Order order = orders.findByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (known(input) || order.getStatus() != OrderStatus.PENDING || !clock.instant().isBefore(order.getExpiresAt()))
            return processing.execute(input);
        try {
            PublishedProduct product = catalog.detail(order.getProductCode());
            if (product == null || !order.getProductId().equals(product.productId()) || !order.getProductCode().equals(product.productCode()))
                throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
            if (!clock.instant().isBefore(order.getExpiresAt())) return processing.execute(input);
            if ("BLOCKED".equals(product.saleStatus())) throw new BusinessException(OrderErrorCode.PRODUCT_SALE_RESTRICTED);
        } catch (RuntimeException failure) {
            // Another delivery may have durably admitted this same command during the lookup.
            if (known(input)) return processing.execute(input);
            throw failure;
        }
        return processing.execute(input);
    }

    private boolean known(PaymentAdmissionInput input) { return admissions.findByAdmissionId(input.admissionId()).isPresent(); }
}
