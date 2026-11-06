package com.stove.license;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.test.PausePoints;
import com.stove.common.test.PausePoints.Gate;
import com.stove.common.test.PausePoints.Run;
import com.stove.common.test.PausePoints.Watch;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseErrorCode;
import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.PurchaseRefund;
import com.stove.license.core.domain.PurchaseStatus;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.service.ApplyLicenseRestoration;
import com.stove.license.core.service.ApplyLicenseSuspension;
import com.stove.license.core.service.ApplyPurchaseRefund;
import com.stove.license.core.service.GrantLicense;
import com.stove.license.core.service.RegisterLicense;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결정 103: 구매 근거를 바꾸는 두 기능이 엇갈리는 순서를 멈춤 지점으로 고정해 실제 서비스끼리 재현한다.
 * "잠금 전 읽기" 지점은 잠금 뒤 다시 읽는지(PR #116 R1), "잠금 뒤" 지점은 상대가 잠금에 막혀 기다리는지 본다.
 */
class PurchaseInterleavingIntegrationTest extends LicenseIntegrationTestBase {
    static final String BUYER = "usr_interleave";
    static final String READ_OWNED = "LicenseRepository.findByBuyerSubjectAndLicenseId";
    static final String READ_ORDER = "LicenseRepository.findByOrderNo";
    static final String LOCKED = "LicenseRepository.lockProductPurchases";
    static final String LOCK_ORDER = "LicenseRepository.lockByOrderNo";
    static final Duration BLOCKED = Duration.ofSeconds(1);
    static final PausePoints POINTS = new PausePoints();

    @TestConfiguration
    static class Pausing {
        @Bean
        static BeanPostProcessor pausingLicenseRepository() {
            return PausePoints.pausing(POINTS, LicenseRepository.class);
        }
    }

    @Autowired GrantLicense grant;
    @Autowired RegisterLicense register;
    @Autowired ApplyLicenseSuspension suspend;
    @Autowired ApplyLicenseRestoration restore;
    @Autowired ApplyPurchaseRefund refund;
    @Autowired LicenseRepository licenses;
    @Autowired LicenseRegistrationRepository registrations;
    @Autowired LicenseSuspensionRepository suspensions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        assertThat(POINTS.reset()).as("쓰이지 않은 멈춤 지점").isEmpty();
        jdbc.update("DELETE FROM license_approval_correction"); jdbc.update("DELETE FROM license_restoration");
        jdbc.update("DELETE FROM license_suspension"); jdbc.update("DELETE FROM license_purchase");
        jdbc.update("DELETE FROM license_registration"); jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("1. 등록이 상태를 읽은 뒤 환불 정지가 커밋되면, 등록은 정지를 보고 거절되며 정지를 덮어쓰지 않는다")
    void registrationReadBeforeCommittedSuspensionIsRefused() throws Exception {
        License purchase = bought();
        Gate read = POINTS.arm(READ_OWNED, "register");
        Run<LicenseRegistration> registering = PausePoints.start("register", () -> register.execute(BUYER, purchase.getLicenseId()));
        read.awaitArrival();

        suspend.execute(stop(purchase));
        read.release();

        assertThat(registering.failure()).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(LicenseErrorCode.PURCHASE_NOT_REGISTRABLE));
        License after = reload(purchase);
        assertThat(after.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
        assertThat(after.getSuspendedFrom()).isEqualTo(PurchaseStatus.STORED);
        assertThat(count("license_registration")).isZero();
    }

    @Test
    @DisplayName("1. 등록이 잠근 동안 환불 정지는 기다리고, 등록이 끝나면 새 보유권까지 정지한다")
    void suspensionWaitsForTheRegistrationLockAndThenSuspendsTheNewLicense() throws Exception {
        License purchase = bought();
        Gate locked = POINTS.arm(LOCKED, "register");
        Run<LicenseRegistration> registering = PausePoints.start("register", () -> register.execute(BUYER, purchase.getLicenseId()));
        locked.awaitArrival();

        Watch waiting = POINTS.watch(LOCK_ORDER, "suspend");
        Run<Void> suspending = PausePoints.start("suspend", () -> { suspend.execute(stop(purchase)); return null; });
        assertThat(waiting.stillInsideAfter(BLOCKED)).as("등록이 잠근 동안 정지는 잠금 호출 안에서 기다린다").isTrue();
        locked.release();

        assertThat(registering.join().getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        suspending.join();
        License after = reload(purchase);
        assertThat(after.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
        assertThat(after.getSuspendedFrom()).isEqualTo(PurchaseStatus.REGISTERED);
        assertThat(registrationStatuses(purchase)).containsExactly(LicenseStatus.SUSPENDED);
    }

    @Test
    @DisplayName("2. 복구가 상태를 읽은 뒤 환불 완료가 커밋되면, 복구는 거절되고 끝난 구매는 되살아나지 않는다")
    void restorationReadBeforeCommittedRefundIsRefused() throws Exception {
        License purchase = bought();
        register.execute(BUYER, purchase.getLicenseId());
        SuspensionRequest stop = stop(purchase);
        suspend.execute(stop);
        RestorationRequest restoring = restoration(purchase, stop);
        Gate read = POINTS.arm(READ_ORDER, "restore");
        Run<Void> restoration = PausePoints.start("restore", () -> { restore.execute(restoring); return null; });
        read.awaitArrival();

        refund.execute(refunded(purchase, stop));
        read.release();

        assertThat(restoration.failure()).isInstanceOf(BusinessException.class);
        assertThat(reload(purchase).getStatus()).isEqualTo(PurchaseStatus.REFUNDED);
        assertThat(registrationStatuses(purchase)).containsExactly(LicenseStatus.ENDED);
        assertThat(count("license_restoration")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestored'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("2. 등록 전 보관함 구매도, 복구가 읽은 뒤 환불 완료가 커밋되면 복구는 거절되고 환불 완료가 남는다")
    void storedPurchaseRestorationReadBeforeCommittedRefundIsRefused() throws Exception {
        // 보유권이 없어 보유권 상태 확인이 끼어들지 않는다. 잠근 뒤 다시 읽기만 이 순서를 막는다.
        License purchase = bought();
        SuspensionRequest stop = stop(purchase);
        suspend.execute(stop);
        RestorationRequest restoring = restoration(purchase, stop);
        Gate read = POINTS.arm(READ_ORDER, "restore");
        Run<Void> restoration = PausePoints.start("restore", () -> { restore.execute(restoring); return null; });
        read.awaitArrival();

        refund.execute(refunded(purchase, stop));
        read.release();

        assertThat(restoration.failure()).isInstanceOf(BusinessException.class);
        License after = reload(purchase);
        assertThat(after.getStatus()).isEqualTo(PurchaseStatus.REFUNDED);
        assertThat(after.getRefundedAt()).isNotNull();
        assertThat(count("license_restoration")).isZero();
    }

    @Test
    @DisplayName("3. 새 구매 등록이 상태를 읽은 뒤 옛 구매 복구가 커밋되면, 새 등록은 이미 보유로 거절되고 보유권은 하나다")
    void newRegistrationReadBeforeCommittedRestorationIsRefusedAsOwned() throws Exception {
        License first = bought();
        register.execute(BUYER, first.getLicenseId());
        SuspensionRequest stop = stop(first);
        suspend.execute(stop);
        License second = bought();
        Gate read = POINTS.arm(READ_OWNED, "register");
        Run<LicenseRegistration> registering = PausePoints.start("register", () -> register.execute(BUYER, second.getLicenseId()));
        read.awaitArrival();

        restore.execute(restoration(first, stop));
        read.release();

        assertThat(registering.failure()).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(LicenseErrorCode.PRODUCT_ALREADY_OWNED));
        assertThat(reload(first).getStatus()).isEqualTo(PurchaseStatus.REGISTERED);
        assertThat(registrationStatuses(first)).containsExactly(LicenseStatus.ACTIVE);
        assertThat(reload(second).getStatus()).isEqualTo(PurchaseStatus.STORED);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 옛 구매 복구가 상태를 읽은 뒤 새 구매 등록이 커밋되면, 옛 구매는 보관함으로 돌아가고 보유권은 하나다")
    void restorationReadBeforeCommittedNewRegistrationReturnsTheOldPurchase() throws Exception {
        License first = bought();
        register.execute(BUYER, first.getLicenseId());
        SuspensionRequest stop = stop(first);
        suspend.execute(stop);
        License second = bought();
        RestorationRequest restoring = restoration(first, stop);
        Gate read = POINTS.arm(READ_ORDER, "restore");
        Run<Void> restoration = PausePoints.start("restore", () -> { restore.execute(restoring); return null; });
        read.awaitArrival();

        register.execute(BUYER, second.getLicenseId());
        read.release();

        restoration.join();
        assertReturnedToStorage(first, second);
    }

    @Test
    @DisplayName("3. 새 구매 등록이 잠근 동안 옛 구매 복구는 기다리고, 등록이 끝나면 옛 구매를 보관함으로 돌려보낸다")
    void restorationWaitsForTheNewRegistrationLock() throws Exception {
        License first = bought();
        register.execute(BUYER, first.getLicenseId());
        SuspensionRequest stop = stop(first);
        suspend.execute(stop);
        License second = bought();
        RestorationRequest restoring = restoration(first, stop);
        Gate locked = POINTS.arm(LOCKED, "register");
        Run<LicenseRegistration> registering = PausePoints.start("register", () -> register.execute(BUYER, second.getLicenseId()));
        locked.awaitArrival();

        Watch waiting = POINTS.watch(LOCKED, "restore");
        Run<Void> restoration = PausePoints.start("restore", () -> { restore.execute(restoring); return null; });
        assertThat(waiting.stillInsideAfter(BLOCKED)).as("등록이 잠근 동안 복구는 잠금 호출 안에서 기다린다").isTrue();
        locked.release();

        assertThat(registering.join().getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        restoration.join();
        assertReturnedToStorage(first, second);
    }

    private void assertReturnedToStorage(License first, License second) {
        assertThat(reload(first).getStatus()).isEqualTo(PurchaseStatus.STORED);
        assertThat(registrationStatuses(first)).containsExactly(LicenseStatus.ENDED);
        assertThat(registrationStatuses(second)).containsExactly(LicenseStatus.ACTIVE);
        assertThat(activeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT returned_to_storage FROM license_restoration", Boolean.class)).isTrue();
    }

    private License bought() {
        PaidOrder order = paid(BUYER, 1L, "Game-1");
        grant.execute(order);
        return licenses.findByOrderNo(order.orderNo()).orElseThrow();
    }

    private License reload(License purchase) {
        return licenses.findByOrderNo(purchase.getOrderNo()).orElseThrow();
    }

    private SuspensionRequest stop(License purchase) {
        return new SuspensionRequest(id(), Instant.parse("2026-11-06T01:00:00.123456Z"), id(), purchase.getOrderNo(), BUYER,
                purchase.getProductId(), purchase.getProductCode(), purchase.getPaymentId());
    }

    private RestorationRequest restoration(License purchase, SuspensionRequest stop) {
        LicenseSuspension suspended = suspensions.findByRequestEventId(stop.eventId()).orElseThrow();
        return new RestorationRequest(id(), suspended.getSuspendedAt(), stop.refundId(), id(), id(), purchase.getLicenseId(),
                purchase.getOrderNo(), BUYER, purchase.getProductId(), purchase.getProductCode(), purchase.getPaymentId(),
                stop.eventId(), suspended.getCompletedEventId());
    }

    private PurchaseRefund refunded(License purchase, SuspensionRequest stop) {
        return new PurchaseRefund(id(), Instant.parse("2026-11-06T02:00:00.123456Z"), stop.refundId(), purchase.getLicenseId(),
                purchase.getOrderNo(), BUYER, purchase.getProductId(), purchase.getProductCode(), purchase.getPaymentId(), stop.eventId());
    }

    private List<LicenseStatus> registrationStatuses(License purchase) {
        return registrations.findAll().stream().filter(row -> row.getLicenseId().equals(purchase.getLicenseId()))
                .map(LicenseRegistration::getStatus).toList();
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM license_registration WHERE status='ACTIVE'", Integer.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
