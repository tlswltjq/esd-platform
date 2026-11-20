package com.stove.common.messaging.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** 제자리 재시도({@link ConsumerRetryPolicy}) 뒤 recoverer 로 격리하는 오류 처리기를 만든다. 격리처는 소비 모듈 DB(결정 206) 또는 DLT(Store). */
@Slf4j
public final class DeadLetterErrorHandlers {

    private DeadLetterErrorHandlers() {
    }

    /**
     * @param recoverer 재시도를 다 쓴 레코드를 받는다(보통 {@link DeadLetterPublisher}).
     * @param noRetryFor 재시도해도 결과가 같은 예외. 던지면 바로 {@code recoverer} 로 간다.
     */
    @SafeVarargs
    public static DefaultErrorHandler create(ConsumerRecordRecoverer recoverer,
                                             Class<? extends Exception>... noRetryFor) {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("재시도 소진(또는 재시도 제외) — 격리한다 topic={} partition={} offset={} key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
            recoverer.accept(record, exception);
        }, ConsumerRetryPolicy.backOff());
        if (noRetryFor.length > 0) {
            handler.addNotRetryableExceptions(noRetryFor);
        }
        return handler;
    }
}
