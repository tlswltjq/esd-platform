package com.stove.bench.messaging.app.core.service;

import com.stove.bench.messaging.app.core.domain.BenchInjectedFailure;
import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.domain.BenchRecord;
import com.stove.bench.messaging.app.core.domain.BenchRecordRepository;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시험 리스너의 "업무": Inbox 기록과 시험 표 한 행을 한 트랜잭션으로 쓴다(E13 층 2). 운영 소비자와 같은 격리 수준(READ COMMITTED,
 * 결정 215)·같은 Inbox 가드를 쓴다.
 *
 * <p>주입 실패는 두 기록을 DB에 보낸(flush) <b>뒤</b> 던진다. 그래야 실패가 실제 롤백을 거치고, 재전달이 Inbox에 걸리지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RecordBenchMessage {

    public static final String CONSUMER_GROUP = "bench-messaging";

    private final ProcessedEventGuard inbox;
    private final BenchRecordRepository records;
    private final EntityManager entityManager;
    private final Clock clock;

    /** @return 반영했으면 true, Inbox가 이미 처리한 메시지라 건너뛰었으면 false. */
    public boolean execute(BenchMessage message, int attempt) {
        if (!inbox.firstDelivery(message.eventId(), CONSUMER_GROUP, BenchMessage.EVENT_TYPE)) {
            return false;
        }
        records.save(BenchRecord.of(message, attempt, ChronoUnit.MICROS.between(Instant.EPOCH, clock.instant())));
        entityManager.flush();
        if (message.failure().failsOn(attempt)) {
            throw new BenchInjectedFailure(message.eventId(), attempt, message.failure());
        }
        return true;
    }
}
