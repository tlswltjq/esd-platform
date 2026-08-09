package com.stove.license.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.kafka.ConsumerRetryPolicy;
import com.stove.common.kafka.DeadLetterMetrics;
import com.stove.common.kafka.DeadLetterPublisher;
import com.stove.license.core.service.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;

/**
 * 컨슈머 재시도 정책과 Saga 보상 진입점.
 *
 * <p>두 가지를 명시적으로 정한다.
 * <ol>
 *   <li><b>얼마나 버틸 것인가</b> — {@link ConsumerRetryPolicy} 의 공용 기본값을 쓴다.
 *       서비스마다 재시도 정책이 다르면 장애 시 무슨 일이 벌어질지 예측할 수 없다.</li>
 *   <li><b>포기한 뒤 무엇을 할 것인가</b> — 리스너 안에서 예외를 잡으면 재시도가 아예 돌지 않으므로
 *       보상 트리거는 여기(recoverer)에 둔다. recoverer 는 정의상 재시도가 전부 소진된 뒤에만 불린다.</li>
 * </ol>
 *
 * <p>이 빈이 있으면 {@code common:messaging} 의 기본 에러 핸들러는 물러난다
 * ({@code @ConditionalOnMissingBean}). 기본값은 기록하고 건너뛸 뿐 보상을 시작하지 않기 때문이다.
 */
@Slf4j
@Configuration
public class KafkaErrorHandlerConfig {

    /**
     * 재시도 소진 후의 최종 처리. Saga 보상은 <b>여기서만</b> 시작된다.
     *
     * <p>이 안에서 예외가 나가면 레코드가 다시 되감겨 무한 재전송이 된다.
     * 마지막 방어선이므로 무엇이 터지든 로그로 끝낸다.
     *
     * <p><b>DLT 로 보내는 경우와 보내지 않는 경우가 갈린다.</b> 다른 서비스는 재시도가 소진되면
     * 무조건 DLT 로 보내지만(유실 방지), 여기는 그렇게 하면 안 되는 경로가 하나 있다.
     *
     * <ul>
     *   <li><b>지급 실패(보상함)</b> — DLT 로 <b>보내지 않는다.</b> 보상이 이미 최종 처리이기 때문이다.
     *       이 시점에 {@code LicenseIssueFailed} 가 나가 payment 가 자동 환불한다.
     *       그런데 리스너가 실패했으므로 Inbox 가드 행은 롤백돼 있다 — 즉 <b>재투입하면 멱등 가드가
     *       막아주지 않고 지급이 다시 시도된다.</b> 이미 환불된 결제에 라이선스를 발급하게 된다.</li>
     *   <li><b>보상 대상이 아닌 실패</b>(회수 실패 등) — DLT 로 보낸다. 보상이 걸리지 않으므로
     *       여기서 버리면 그냥 유실이고, 원인을 고친 뒤 재투입하는 것이 맞다.</li>
     *   <li><b>봉투를 풀 수 없는 레코드</b> — DLT 로 보낸다. 무슨 사건인지도 모르므로
     *       판단을 사람에게 넘긴다.</li>
     * </ul>
     */
    @Bean
    public ConsumerRecordRecoverer licenseIssueFailureRecoverer(LicenseService licenseService,
                                                                ObjectMapper objectMapper,
                                                                KafkaTemplate<String, String> kafkaTemplate,
                                                                DeadLetterMetrics deadLetterMetrics) {
        return recoverer(licenseService, objectMapper,
                DeadLetterPublisher.to(kafkaTemplate, deadLetterMetrics));
    }

    /**
     * DLT 단계를 인자로 받는다 — 테스트가 대역으로 갈아끼울 수 있게 하기 위해서다.
     *
     * <p>여기서 검증할 것은 브로커 발행이 아니라 <b>어느 경로가 DLT 로 가고 어느 경로가 안 가는가</b>다.
     * 진짜 {@code DeadLetterPublishingRecoverer} 를 세우면 그 판단을 브로커 대역 뒤에 숨기게 된다.
     */
    static ConsumerRecordRecoverer recoverer(LicenseService licenseService,
                                             ObjectMapper objectMapper,
                                             ConsumerRecordRecoverer toDeadLetterTopic) {
        return (record, exception) -> {
            @SuppressWarnings("unchecked")
            ConsumerRecord<String, String> consumed = (ConsumerRecord<String, String>) record;
            try {
                EventEnvelope envelope = EventEnvelope.from(consumed);
                if (!envelope.isType(EventType.PAYMENT_COMPLETED)) {
                    // 회수 실패 등은 결제를 되돌릴 일이 아니다. 보상 대상은 지급 실패뿐이다.
                    log.error("재시도 소진 — 보상 대상 아님, DLT 로 보낸다 type={} key={}",
                            envelope.eventType(), envelope.key(), exception);
                    sendQuietly(toDeadLetterTopic, consumed, exception);
                    return;
                }
                PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);
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

    /**
     * DLT 발행 실패까지 삼킨다.
     *
     * <p>브로커가 죽어 있으면 여기서도 예외가 난다. 그것이 밖으로 나가면 레코드가 되감겨
     * <b>무한 재전송</b>이 되므로, 마지막 방어선의 계약("무엇이 터지든 로그로 끝낸다")을 지킨다.
     */
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

    /** 보상 사유는 결제 서비스 로그에 남으므로 원인 예외까지 드러낸다. */
    private static String reasonOf(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "%s: %s".formatted(root.getClass().getSimpleName(), root.getMessage());
    }
}
