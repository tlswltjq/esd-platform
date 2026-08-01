package com.stove.payment.infrastructure.pg;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로컬/테스트용 PG 스텁. 실제 연동체는 같은 인터페이스를 구현해 프로파일로 교체한다.
 */
@Slf4j
@Component
public class MockPgClient implements PgClient {

    @Override
    public PgPrepareResult prepare(String orderNo, long amount, String currency, String method) {
        String pgTxId = "PG-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        log.info("[MOCK PG] 사전등록 orderNo={} amount={}{} method={} → pgTxId={}",
                orderNo, amount, currency, method, pgTxId);
        return new PgPrepareResult(pgTxId, "https://mock-pg.local/checkout/" + pgTxId);
    }

    @Override
    public void cancel(String pgTxId, long amount, String reason) {
        log.info("[MOCK PG] 취소 pgTxId={} amount={} reason={}", pgTxId, amount, reason);
    }
}
