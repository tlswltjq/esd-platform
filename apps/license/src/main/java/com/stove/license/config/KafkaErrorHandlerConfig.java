package com.stove.license.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.license.core.service.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

/**
 * 컨슈머 재시도 정책과 Saga 보상 진입점.
 *
 * <p>두 가지를 명시적으로 정한다.
 * <ol>
 *   <li><b>얼마나 버틸 것인가</b> — 기본값은 {@code FixedBackOff(0ms, 9회)} 라
 *       10회가 수 밀리초 만에 소진된다. 커넥션풀 순간 고갈처럼 흔한 장애를 못 넘긴다.</li>
 *   <li><b>포기한 뒤 무엇을 할 것인가</b> — 리스너 안에서 예외를 잡으면 재시도가 아예 돌지 않으므로
 *       보상 트리거는 여기(recoverer)에 둔다. recoverer 는 정의상 재시도가 전부 소진된 뒤에만 불린다.</li>
 * </ol>
 *
 * <p>블로킹 재시도라 대기 시간만큼 파티션이 멈춘다. 총 대기가 {@code max.poll.interval.ms}(기본 5분)를
 * 넘으면 컨슈머가 그룹에서 쫓겨나므로 여유를 크게 두고 잡았다(1+2+4 = 7초).
 */
@Slf4j
@Configuration
public class KafkaErrorHandlerConfig {

    /** 재시도 횟수. 최초 배달 1회 + 재시도 3회 = 총 4회 시도. */
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MS = 1_000L;
    private static final long MAX_INTERVAL_MS = 8_000L;
    private static final double MULTIPLIER = 2.0;

    /**
     * 재시도 소진 후의 최종 처리. Saga 보상은 <b>여기서만</b> 시작된다.
     *
     * <p>이 안에서 예외가 나가면 레코드가 다시 되감겨 무한 재전송이 된다.
     * 마지막 방어선이므로 무엇이 터지든 로그로 끝낸다.
     */
    @Bean
    public ConsumerRecordRecoverer licenseIssueFailureRecoverer(LicenseService licenseService,
                                                                ObjectMapper objectMapper) {
        return (record, exception) -> {
            @SuppressWarnings("unchecked")
            ConsumerRecord<String, String> consumed = (ConsumerRecord<String, String>) record;
            try {
                EventEnvelope envelope = EventEnvelope.from(consumed);
                if (!envelope.isType(EventType.PAYMENT_COMPLETED)) {
                    // 회수 실패 등은 결제를 되돌릴 일이 아니다. 보상 대상은 지급 실패뿐이다.
                    log.error("재시도 소진 — 보상 대상 아님 type={} key={}",
                            envelope.eventType(), envelope.key(), exception);
                    return;
                }
                PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);
                log.error("라이선스 지급 최종 실패 orderNo={}", event.orderNo(), exception);
                licenseService.recordIssueFailure(event.orderNo(), event.memberId(), reasonOf(exception));

            } catch (Exception recoveryFailure) {
                log.error("보상 처리 자체가 실패했다 — 수동 확인 필요 topic={} partition={} offset={}",
                        consumed.topic(), consumed.partition(), consumed.offset(), recoveryFailure);
            }
        };
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerRecordRecoverer licenseIssueFailureRecoverer) {
        return new DefaultErrorHandler(licenseIssueFailureRecoverer, backOff());
    }

    /** 재시도 정책. 테스트가 정책 자체를 확인할 수 있도록 패키지 공개로 둔다. */
    static BackOff backOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        return backOff;
    }

    /** 보상 사유는 결제 서비스 로그에 남으므로 원인 예외까지 드러낸다. */
    private static String reasonOf(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "%s: %s".formatted(root.getClass().getSimpleName(), root.getMessage());
    }
}
