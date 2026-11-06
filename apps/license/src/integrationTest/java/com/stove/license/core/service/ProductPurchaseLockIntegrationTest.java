package com.stove.license.core.service;

import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.license.LicenseIntegrationTestBase;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.PurchaseStatus;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PR #116 리뷰 R1: 잠금 전에 읽은 구매 근거 상태로 등록·복구를 판단하지 않는다. */
class ProductPurchaseLockIntegrationTest extends LicenseIntegrationTestBase {
    @Autowired GrantLicense grant;
    @Autowired LicenseRepository licenses;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM license_registration");
        jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("잠금 전에 읽힌 뒤 다른 트랜잭션이 정지를 커밋해도 잠근 구매 근거는 커밋된 정지 상태다")
    void lockedPurchaseReflectsSuspensionCommittedAfterAnEarlierRead() {
        PaidOrder order = paid();
        grant.execute(order);
        String licenseId = licenses.findByOrderNo(order.orderNo()).orElseThrow().getLicenseId();

        License locked = new TransactionTemplate(transactions).execute(tx -> {
            // 같은 트랜잭션에서 먼저 읽어 영속성 컨텍스트에 STORED로 올린다.
            assertThat(licenses.findByBuyerSubjectAndLicenseId(order.buyerSubject(), licenseId).orElseThrow().getStatus())
                    .isEqualTo(PurchaseStatus.STORED);
            // 그 사이 다른 트랜잭션이 환불 정지를 커밋한다.
            CompletableFuture.runAsync(() -> jdbc.update("UPDATE product_license SET status='SUSPENDED', suspended_from='STORED', "
                    + "suspension_request_event_id='suspend-r1' WHERE license_id=?", licenseId)).join();
            return ProductPurchaseLock.lockOwned(licenses, entityManager, order.buyerSubject(), licenseId);
        });

        assertThat(locked.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
        assertThat(locked.getSuspensionRequestEventId()).isEqualTo("suspend-r1");
    }
}
