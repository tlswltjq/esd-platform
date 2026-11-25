package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.service.ManageAccount;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자의 계정 상태·역할 관리를 실제 MySQL에서 검증한다.
 *
 * <p>상태 전환·역할 회수가 DB에 반영되고, 변경마다 {@code role_audit_log}에 감사가 남으며, 대상
 * 계정의 Refresh Token 계열이 즉시 폐기되며, 계정 접근 변경 사실({@code AccountAccessChanged})이 같은 트랜잭션에서
 * Outbox에 쌓이는지를 끝까지 본다(R7.6, 결정 180). 요청자의 현재 권한은 토큰과 별개로 DB에서 다시 본다(R7.1, AU-T6).
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false", "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class AccountManagementIntegrationTest {

    @Autowired ManageAccount manageAccount;
    @Autowired UserAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private UserAccount persistAdmin() {
        return accounts.saveAndFlush(UserAccount.withRoles(
                "admin-" + System.nanoTime() + "@example.com", "{bcrypt}h",
                Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER)));
    }

    private UserAccount persistMember() {
        return accounts.saveAndFlush(UserAccount.member(
                "member-" + System.nanoTime() + "@example.com", "{bcrypt}h"));
    }

    private int auditCount(String subject, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM role_audit_log WHERE subject = ? AND action = ?",
                Integer.class, subject, action);
    }

    private void seedRefreshFamily(String subject) {
        // 이 계정에 살아 있는 Refresh Token 계열이 있다고 가정하고, 변경 후 폐기되는지 본다.
        jdbc.update("""
                INSERT INTO refresh_token_family (authorization_id, client_id, subject, expires_at, revoked)
                VALUES (?, 'gateway-bff', ?, ?, FALSE)
                """, "auth-" + subject, subject, Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private boolean familyRevoked(String subject) {
        return jdbc.queryForObject("SELECT revoked FROM refresh_token_family WHERE subject = ?",
                Boolean.class, subject);
    }

    private List<Map<String, Object>> accessChanged(String subject) {
        return jdbc.queryForList("""
                SELECT topic, partition_key, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.changeKind')) AS change_kind,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.subject')) AS subject
                FROM outbox_event WHERE event_type = 'AccountAccessChanged' AND aggregate_id = ? ORDER BY id
                """, subject);
    }

    @Test
    void changeStatusPersistsRevokesTokensAndAudits() {
        UserAccount admin = persistAdmin();
        UserAccount target = persistMember();
        seedRefreshFamily(target.getSubject());

        manageAccount.changeStatus(admin.getSubject(), target.getSubject(), AccountStatus.LOCKED);

        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.LOCKED);
        assertThat(familyRevoked(target.getSubject())).isTrue();
        assertThat(auditCount(target.getSubject(), "STATUS_CHANGED")).isEqualTo(1);
        assertThat(accessChanged(target.getSubject())).singleElement().satisfies(row -> {
            assertThat(row.get("topic")).isEqualTo("stove.auth.v1");
            assertThat(row.get("partition_key")).isEqualTo(target.getSubject());
            assertThat(row.get("subject")).isEqualTo(target.getSubject());
            assertThat(row.get("change_kind")).isEqualTo("STATUS");
        });
    }

    @Test
    void grantAndRevokeRolePersistsAndAudits() {
        UserAccount admin = persistAdmin();
        UserAccount target = persistMember();

        manageAccount.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isTrue();

        manageAccount.revokeRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isFalse();

        assertThat(auditCount(target.getSubject(), "ROLE_CHANGED")).isEqualTo(2);
        assertThat(accessChanged(target.getSubject())).extracting(row -> row.get("change_kind")).containsExactly("ROLE", "ROLE");

        // 이미 가진 역할의 부여는 바뀐 것이 없으므로 사실을 내지 않는다.
        manageAccount.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.MEMBER);
        assertThat(accessChanged(target.getSubject())).hasSize(2);
    }

    /** AU-T6 재현: A가 B의 ADMIN을 회수한 뒤, B가 남은 토큰(ADMIN 역할)으로 다른 계정의 역할을 부여하려 한다. */
    @Test
    void anOperatorWhoLostAdminCannotChangeAccountsWithTheRemainingToken() {
        UserAccount a = persistAdmin();
        UserAccount b = persistAdmin();
        UserAccount target = persistMember();
        manageAccount.revokeRole(a.getSubject(), b.getSubject(), PlatformRole.ADMIN);

        assertThatThrownBy(() -> manageAccount.grantRole(b.getSubject(), target.getSubject(), PlatformRole.CREATOR))
                .isInstanceOfSatisfying(BusinessException.class,
                        rejected -> assertThat(rejected.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isFalse();
        assertThat(accessChanged(target.getSubject())).isEmpty();
    }

    /**
     * 요청자의 ADMIN을 DB에서 떼어 낸 뒤의 회수 시도. R7.1로 마지막 운영자 보호보다 먼저 403이다 — 활성 운영자인 요청자가
     * 늘 남으므로 다른 운영자의 ADMIN 회수로는 운영자 0명이 될 수 없다(마지막 운영자 보호는 방어선으로 남긴다).
     */
    @Test
    void lastAdminRoleCannotBeRevoked() {
        // 깨끗한 상태에서 ADMIN을 정확히 한 명으로 만든다.
        jdbc.update("DELETE r FROM user_account_role r WHERE r.role = 'ADMIN'");
        UserAccount actingAdmin = persistAdmin();
        UserAccount soleAdmin = persistAdmin();
        // actingAdmin의 ADMIN을 떼어 soleAdmin만 ADMIN으로 남긴다.
        jdbc.update("DELETE r FROM user_account_role r JOIN user_account a ON a.id = r.user_account_id "
                + "WHERE a.subject = ? AND r.role = 'ADMIN'", actingAdmin.getSubject());

        assertThatThrownBy(() -> manageAccount.revokeRole(actingAdmin.getSubject(), soleAdmin.getSubject(),
                PlatformRole.ADMIN)).isInstanceOfSatisfying(BusinessException.class,
                        rejected -> assertThat(rejected.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(accounts.findBySubject(soleAdmin.getSubject()).orElseThrow().hasRole(PlatformRole.ADMIN)).isTrue();
    }

    /**
     * PR #244 R1 재현: A가 B의 ADMIN을 회수하는 트랜잭션이 아직 커밋되지 않은 사이 B가 다른 계정을 바꾸려 한다. B의 요청은
     * 회수 커밋을 기다린 뒤 403이어야 한다. 잠금이 없으면 커밋 전 스냅샷의 ADMIN을 보고 변경을 해 버린다.
     */
    @Test
    void anOperatorLosingAdminConcurrentlyCannotFinishAChange() throws Exception {
        UserAccount a = persistAdmin();
        UserAccount b = persistAdmin();
        UserAccount target = persistMember();
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                manageAccount.revokeRole(a.getSubject(), b.getSubject(), PlatformRole.ADMIN);
                revoked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> grant = pool.submit(() -> manageAccount.grantRole(b.getSubject(), target.getSubject(), PlatformRole.CREATOR));
            awaitLockWait("for update");
            assertThat(grant.isDone()).as("B의 요청은 회수 커밋을 기다린다").isFalse();
            release.countDown();
            revoke.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> grant.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(BusinessException.class,
                            rejected -> assertThat(rejected.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 다른 연결이 {@code user_account} 행 잠금을 기다리는 중임을 DB에서 확인한다(PR #244 R7). 같은 DB 사용자의 연결은
     * PROCESS 권한 없이도 {@code information_schema.PROCESSLIST}에 보인다. 잠금 질의가 끝나지 않고 남아 있으면 대기 중이다.
     */
    private void awaitLockWait(String lockClause) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                    WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query' AND LOWER(INFO) LIKE ?
                    """, Integer.class, "%from user_account%" + lockClause + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("다른 연결이 user_account " + lockClause + " 잠금을 기다리지 않는다");
    }
}
