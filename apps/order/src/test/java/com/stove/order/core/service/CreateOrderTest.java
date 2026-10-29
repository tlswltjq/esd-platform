package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static com.stove.order.OrderFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PaymentAdmissionInput;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class CreateOrderTest {
    private static final String BUYER = "usr_buyer";

    @Mock private OrderRepository orders;
    @Mock private CatalogProducts catalog;
    @Mock private OutboxRecorder outbox;
    @Mock private PlatformTransactionManager transactions;
    @InjectMocks private CreateOrder create;

    @ParameterizedTest
    @MethodSource("invalidBuyers")
    void rejectsAnInvalidBuyerBeforeAnyExternalOrPersistenceWork(String buyer) {
        OrderRequest input = request();

        assertThatThrownBy(() -> create.execute(buyer, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, catalog, outbox, transactions);
    }

    @Test
    void rejectsAMissingRequestBeforeAnyExternalOrPersistenceWork() {
        assertThatThrownBy(() -> create.execute(BUYER, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, catalog, outbox, transactions);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAYMENT_PROCESSING", "PAID", "FAILED", "EXPIRED"})
    void theSameRequestReturnsItsCurrentOrderWithoutCallingCatalogOrStartingAnotherPayment(OrderStatus status) {
        OrderRequest input = request();
        Order stored = Order.create(BUYER, input, product(), id());
        if (status == OrderStatus.PAID) {
            stored.apply(result(completed(stored)));
        } else if (status == OrderStatus.FAILED) {
            PaymentResult failed = new PaymentResult(id(), stored.getCreatedAt(), stored.getOrderNo(),
                    stored.getRequestEventId(), id(), BUYER, stored.getAmount(), stored.getCurrency(),
                    OrderStatus.FAILED, "DECLINED", "승인이 거절되었습니다.");
            stored.apply(failed);
        }
        if (status == OrderStatus.PAYMENT_PROCESSING) {
            stored.beginPaymentProcessing(new PaymentAdmissionInput(id(), stored.getOrderNo(), stored.getRequestEventId(),
                    id(), BUYER, stored.getAmount(), stored.getCurrency()), stored.getCreatedAt());
        } else if (status == OrderStatus.EXPIRED) {
            stored.expire(stored.getExpiresAt());
        }
        when(orders.findByBuyerSubjectAndRequestId(BUYER, input.requestId())).thenReturn(Optional.of(stored));

        assertThat(create.execute(BUYER, input)).isSameAs(stored);
        assertThat(stored.getStatus()).isEqualTo(status);
        verifyNoInteractions(catalog, outbox, transactions);
        verify(orders, never()).saveAndFlush(any(Order.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "version", "amount"})
    void theSameRequestIdWithChangedTermsIsAConflictBeforeCatalogLookup(String changedField) {
        OrderRequest input = request();
        Order stored = Order.create(BUYER, input, product(), id());
        OrderRequest changed = switch (changedField) {
            case "product" -> new OrderRequest(input.requestId(), "Game-2", input.expectedContentVersion(), input.expectedAmount());
            case "version" -> new OrderRequest(input.requestId(), input.productCode(), 4, input.expectedAmount());
            case "amount" -> new OrderRequest(input.requestId(), input.productCode(), input.expectedContentVersion(), 9999);
            default -> throw new IllegalArgumentException(changedField);
        };
        when(orders.findByBuyerSubjectAndRequestId(BUYER, input.requestId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> create.execute(BUYER, changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(catalog, outbox, transactions);
        verify(orders, never()).saveAndFlush(any(Order.class));
    }

    @Test
    void aNewOrderUsesTheBuyerAndCatalogSnapshotAndPublishesMatchingTerms() {
        OrderRequest input = request();
        PublishedProduct published = product();
        when(orders.findByBuyerSubjectAndRequestId(BUYER, input.requestId())).thenReturn(Optional.empty());
        when(catalog.detail(input.productCode())).thenReturn(published);
        when(orders.findPurchaseConflicts(BUYER, published.productId(), published.productCode(),
                List.of(OrderStatus.PENDING, OrderStatus.PAYMENT_PROCESSING, OrderStatus.PAID))).thenReturn(List.of());
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(call -> call.getArgument(0, Order.class));
        allowTransaction();

        Order created = create.execute(BUYER, input);

        assertThat(created.getBuyerSubject()).isEqualTo(BUYER);
        assertThat(created.getTitle()).isEqualTo(published.title());
        assertThat(created.getListAmount()).isEqualTo(published.price());
        assertThat(created.getDiscountAmount()).isZero();
        assertThat(created.getAmount()).isEqualTo(published.price());
        assertThat(created.getStatus()).isEqualTo(OrderStatus.PENDING);
        ArgumentCaptor<OrderPlacedEvent> event = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(outbox).record(eq("PurchaseOrder"), eq(created.getOrderNo()), event.capture());
        OrderPlacedEvent placed = event.getValue();
        assertThat(placed.eventId()).isEqualTo(created.getRequestEventId());
        assertThat(placed.occurredAt()).isEqualTo(created.getCreatedAt());
        assertThat(placed.partitionKey()).isEqualTo(created.getOrderNo());
        assertThat(placed.buyerSubject()).isEqualTo(BUYER);
        assertThat(placed.productId()).isEqualTo(published.productId());
        assertThat(placed.productCode()).isEqualTo(published.productCode());
        assertThat(placed.title()).isEqualTo(published.title());
        assertThat(placed.contentVersion()).isEqualTo(published.contentVersion());
        assertThat(placed.listAmount()).isEqualTo(published.price());
        assertThat(placed.discountAmount()).isZero();
        assertThat(placed.amount()).isEqualTo(created.getAmount());
        assertThat(placed.currency()).isEqualTo(created.getCurrency());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID"})
    void anotherRequestCannotBuyAProductAlreadyPendingOrPaid(OrderStatus previousStatus) {
        OrderRequest input = request();
        PublishedProduct published = product();
        Order previous = Order.create(BUYER, request(), published, id());
        if (previousStatus == OrderStatus.PAID) previous.apply(result(completed(previous)));
        when(catalog.detail(input.productCode())).thenReturn(published);
        when(orders.findPurchaseConflicts(BUYER, published.productId(), published.productCode(),
                List.of(OrderStatus.PENDING, OrderStatus.PAYMENT_PROCESSING, OrderStatus.PAID))).thenReturn(List.of(previous));
        allowTransaction();

        assertThatThrownBy(() -> create.execute(BUYER, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(previousStatus == OrderStatus.PAID
                                ? OrderErrorCode.PRODUCT_ALREADY_PURCHASED : OrderErrorCode.PURCHASE_IN_PROGRESS));

        verify(orders, never()).saveAndFlush(any(Order.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void aCatalogFailureWithoutAnExistingOrderDoesNotCreateAPaymentRequest() {
        OrderRequest input = request();
        when(catalog.detail(input.productCode())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> create.execute(BUYER, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));

        verify(orders, never()).saveAndFlush(any(Order.class));
        verifyNoInteractions(outbox, transactions);
    }

    @Test
    void returnsTheCommittedSameRequestWhenCatalogFailsAfterTheFirstLookup() {
        OrderRequest input = request();
        Order committed = Order.create(BUYER, input, product(), id());
        when(orders.findByBuyerSubjectAndRequestId(BUYER, input.requestId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(committed));
        when(catalog.detail(input.productCode())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        assertThat(create.execute(BUYER, input)).isSameAs(committed);

        verify(orders, never()).saveAndFlush(any(Order.class));
        verifyNoInteractions(outbox, transactions);
    }

    @Test
    void processingAlsoBlocksAnotherPurchaseRequest() {
        OrderRequest input = request();
        Order processing = Order.create(BUYER, request(), product(), id());
        processing.beginPaymentProcessing(new PaymentAdmissionInput(id(), processing.getOrderNo(),
                processing.getRequestEventId(), id(), BUYER, processing.getAmount(), processing.getCurrency()),
                processing.getCreatedAt());
        when(catalog.detail(input.productCode())).thenReturn(product());
        when(orders.findPurchaseConflicts(eq(BUYER), eq(1L), eq("Game-1"), any()))
                .thenAnswer(call -> call.<List<OrderStatus>>getArgument(3).contains(OrderStatus.PAYMENT_PROCESSING)
                        ? List.of(processing) : List.of());
        allowTransaction();
        assertThatThrownBy(() -> create.execute(BUYER, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(OrderErrorCode.PURCHASE_IN_PROGRESS));
    }

    private void allowTransaction() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
    }

    static Stream<String> invalidBuyers() {
        return Stream.of(null, "", " ", "x".repeat(101));
    }
}
