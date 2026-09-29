package com.stove.payment.infrastructure.pg;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.payment.core.domain.PgPreparation;
import com.stove.payment.core.domain.SimulatedPgTransaction;
import com.stove.payment.core.domain.SimulatedPgTransactionRepository;
import com.stove.payment.core.port.PgClient;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 로컬/테스트용 PG 스텁. 실제 연동체는 같은 인터페이스를 구현해 프로파일로 교체한다.
 */
@Slf4j
@Profile("!prod")
@Component
public class MockPgClient implements PgClient {

    private final SimulatedPgTransactionRepository transactions;
    private final String simulatorBaseUrl;
    private final boolean simulatorEnabled;

    public MockPgClient(SimulatedPgTransactionRepository transactions,
                        @Value("${stove.payment.simulator.base-url:http://localhost:8080}") String baseUrl,
                        Environment environment) {
        this.transactions = transactions;
        this.simulatorBaseUrl = baseUrl.replaceAll("/+$", "");
        this.simulatorEnabled = environment.acceptsProfiles(Profiles.of("demo | test"));
    }

    @Override
    public PgPreparation prepare(String orderNo, long amount, String currency, String method) {
        String pgTxId = "PG-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        transactions.save(SimulatedPgTransaction.prepared(pgTxId, orderNo, amount, currency));
        log.info("[MOCK PG] 사전등록 orderNo={} amount={}{} method={} → pgTxId={}",
                orderNo, amount, currency, method, pgTxId);
        String redirectUrl = simulatorEnabled
                ? simulatorBaseUrl + "/api/v1/payments/simulator/checkout/" + pgTxId
                : "https://mock-pg.local/checkout/" + pgTxId;
        return new PgPreparation(pgTxId, redirectUrl);
    }

    @Override
    public void cancel(String pgTxId, long amount, String reason) {
        SimulatedPgTransaction transaction = transactions.findById(pgTxId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        transaction.cancel(amount);
        transactions.save(transaction);
        log.info("[MOCK PG] 취소 pgTxId={} amount={} reason={}", pgTxId, amount, reason);
    }
}
