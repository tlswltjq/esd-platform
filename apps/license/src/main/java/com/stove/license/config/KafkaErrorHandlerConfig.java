package com.stove.license.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.kafka.ConsumerRetryPolicy;
import com.stove.common.kafka.DeadLetterMetrics;
import com.stove.common.kafka.DeadLetterPublisher;
import com.stove.license.core.service.LicenseService;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.BackOff;

/**
 * 컨슈머 재시도 정책과 <b>Saga 보상 진입점.</b> 보상 트리거가 리스너가 아니라 recoverer 에
 * 있어야 하는 이유(리스너에서 잡으면 재시도가 아예 안 돈다)는 docs/code-notes.md
 */
@Slf4j
@Configuration
public class KafkaErrorHandlerConfig {

    /**
     * 재시도 소진 후의 최종 처리. Saga 보상은 <b>여기서만</b> 시작된다.
     *
     * <p><b>이 안에서 예외가 나가면 무한 재전송이 된다</b> — 무엇이 터지든 로그로 끝낸다.
     *
     * <p><b>지급 실패만 DLT 로 보내지 않는다</b>(보상이 이미 최종 처리다). 그리고 보상은
     * 두 관문(원인·결과)을 통과해야 시작된다 [D-027] [D-028]. 근거는 docs/code-notes.md
     */
    @Bean
    public ConsumerRecordRecoverer licenseIssueFailureRecoverer(LicenseService licenseService,
                                                                ObjectMapper objectMapper,
                                                                KafkaTemplate<String, String> kafkaTemplate,
                                                                DeadLetterMetrics deadLetterMetrics) {
        return recoverer(licenseService, objectMapper,
                DeadLetterPublisher.to(kafkaTemplate, deadLetterMetrics));
    }

    /** DLT 단계를 인자로 받는다 — 테스트가 대역으로 갈아끼울 수 있게. docs/code-notes.md */
    static ConsumerRecordRecoverer recoverer(LicenseService licenseService,
                                             ObjectMapper objectMapper,
                                             ConsumerRecordRecoverer toDeadLetterTopic) {
        return (record, exception) -> {
            @SuppressWarnings("unchecked")
            ConsumerRecord<String, String> consumed = (ConsumerRecord<String, String>) record;
            try {
                EventEnvelope envelope = EventEnvelope.from(consumed);
                if (!envelope.isType(EventType.PAYMENT_COMPLETED)) {
                    // 보상 대상은 지급 실패뿐이다.
                    log.error("재시도 소진 — 보상 대상 아님, DLT 로 보낸다 type={} key={}",
                            envelope.eventType(), envelope.key(), exception);
                    sendQuietly(toDeadLetterTopic, consumed, exception);
                    return;
                }
                PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);

                // 관문 1 — 원인. 저장소가 답을 못 준 것은 '지급 불가' 가 아니라 '판단 불가' 다.
                if (isStorageFailure(exception)) {
                    log.error("라이선스 지급 실패가 저장소 장애다 — 환불하지 않고 보류(DLT)한다 orderNo={}",
                            event.orderNo(), exception);
                    sendQuietly(toDeadLetterTopic, consumed, exception);
                    return;
                }

                // 관문 2 — 결과. 이미 지급된 주문이면 '실패' 라는 보고가 사실이 아니다.
                if (licenseService.isIssued(event.orderNo())) {
                    log.error("이미 지급된 주문이라 보상하지 않는다 — 보류(DLT) 후 사람이 본다 orderNo={}",
                            event.orderNo(), exception);
                    sendQuietly(toDeadLetterTopic, consumed, exception);
                    return;
                }

                log.error("라이선스 지급 최종 실패 — 보상으로 종결한다(DLT 로 보내지 않는다) orderNo={}",
                        event.orderNo(), exception);
                licenseService.recordIssueFailure(event.orderNo(), event.memberId(), reasonOf(exception));

            } catch (Exception recoveryFailure) {
                log.error("보상 처리 자체가 실패했다 — DLT 로 보낸다 topic={} partition={} offset={}",
                        consumed.topic(), consumed.partition(), consumed.offset(), recoveryFailure);
                sendQuietly(toDeadLetterTopic, consumed, exception);
            }
        };
    }

    /** DLT 발행 실패까지 삼킨다 — 밖으로 나가면 <b>무한 재전송</b>이 된다. */
    private static void sendQuietly(ConsumerRecordRecoverer toDeadLetterTopic,
                                    ConsumerRecord<String, String> record, Exception cause) {
        try {
            toDeadLetterTopic.accept(record, cause);
        } catch (Exception dltFailure) {
            log.error("DLT 발행마저 실패했다 — 이 레코드는 로그에만 남는다 topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), dltFailure);
        }
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerRecordRecoverer licenseIssueFailureRecoverer) {
        return new DefaultErrorHandler(licenseIssueFailureRecoverer, backOff());
    }

    /** 재시도 정책은 공용 기본값을 그대로 쓴다 — 서비스마다 다르면 장애 대응이 예측 불가능해진다. */
    static BackOff backOff() {
        return ConsumerRetryPolicy.backOff();
    }

    /**
     * 이 실패가 <b>저장소가 답을 못 준 것</b>인가.
     * <b>원인 사슬 전체를 훑어야 한다</b> — 맨 바깥만 보면 거의 항상 놓친다.
     * 넓게 잡은 것은 의도다(틀렸을 때의 비용이 대칭이 아니다). docs/code-notes.md
     */
    static boolean isStorageFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException
                    || cause instanceof TransactionException
                    || cause instanceof SQLException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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
