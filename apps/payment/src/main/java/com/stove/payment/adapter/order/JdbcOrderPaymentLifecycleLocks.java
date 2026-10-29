package com.stove.payment.adapter.order;

import com.stove.payment.core.port.OrderPaymentLifecycleLocks;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class JdbcOrderPaymentLifecycleLocks implements OrderPaymentLifecycleLocks {
    private final JdbcTemplate jdbc;
    public JdbcOrderPaymentLifecycleLocks(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override
    public void ensure(String orderNo,String requestEventId,String buyerSubject,long amount,String currency,Instant expiresAt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Order lifecycle lock requires an existing service transaction");
        jdbc.update("INSERT INTO payment_order_lifecycle(order_no,request_event_id,buyer_subject,amount,currency,expires_at,financial_conflict) "
                +"VALUES(?,?,?,?,?,?,false) ON DUPLICATE KEY UPDATE order_no=order_no",orderNo,requestEventId,buyerSubject,amount,currency,
                LocalDateTime.ofInstant(expiresAt,ZoneOffset.UTC));
    }
}
