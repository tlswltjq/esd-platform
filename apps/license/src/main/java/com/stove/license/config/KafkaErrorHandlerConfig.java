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
     *
     * <p><b>보상은 두 관문을 통과해야 시작된다</b>(D-027 · D-028). 예전에는 관문이 없었다 —
     * "재시도가 소진됐다" 하나만 보고 환불했다. 그런데 재시도 소진은 <b>지급할 수 없다</b>는 뜻이
     * 아니라 <b>네 번 물어봤는데 답을 못 받았다</b>는 뜻이다. 그 둘을 같게 취급하면
     * license 의 DB 가 1분 끊긴 것만으로 정상 결제가 환불된다(실측: 60초 장애에 8건).
     *
     * <ol>
     *   <li><b>원인을 본다</b> — 저장소 장애({@link #isStorageFailure})면 보상하지 않고 DLT 로 보낸다.
     *       환불은 되돌리기 어렵고 보류는 되돌리기 쉽다. 판단이 불확실할 때는 되돌릴 수 있는 쪽으로 간다.</li>
     *   <li><b>결과를 본다</b> — 그 주문에 라이선스가 이미 있으면 보상하지 않는다.
     *       "지급 실패" 라는 보고 자체가 사실이 아니다.</li>
     * </ol>
     *
     * <p>그래서 자동 환불의 조건이 <b>"실패했다"에서 "실패했고, 그 실패가 저장소 탓이 아니며,
     * 실제로 지급되지도 않았다"</b>로 좁아진다. 좁아진 만큼은 DLT 에 쌓이고
     * {@code MessagesDeadLettered}(critical) 가 사람을 부른다 — 조용히 사라지지 않는다.
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

    /**
     * 이 실패가 <b>저장소가 답을 못 준 것</b>인가.
     *
     * <p>세 갈래를 원인 사슬 전체에서 찾는다. 리스너의 예외는 컨테이너를 거치며
     * {@code ListenerExecutionFailedException} 으로 한 번, 스프링 변환기를 거치며 한 번 더
     * 감싸이므로 <b>맨 바깥만 보면 거의 항상 놓친다.</b>
     *
     * <ul>
     *   <li>{@link DataAccessException} — 스프링이 변환한 저장소 예외 전부.
     *       커넥션 고갈·권한 거부·타임아웃·제약 위반이 여기로 모인다.</li>
     *   <li>{@link TransactionException} — 트랜잭션을 <b>열지도 못한</b> 경우.
     *       {@code DataAccessException} 이 아니라서 별도로 잡아야 한다.
     *       실측에서 DB 를 끊었을 때 실제로 나온 것이 이쪽이었다
     *       ({@code CannotCreateTransactionException: Could not open JPA EntityManager}).</li>
     *   <li>{@link SQLException} — 위 둘로 변환되기 전의 날것. 드라이버가 직접 던지는 경로가 남아 있다.</li>
     * </ul>
     *
     * <p>넓게 잡은 것은 의도다. <b>여기서 틀렸을 때의 비용이 대칭이 아니다</b> —
     * 저장소 장애를 놓치면 정상 결제가 환불되고(되돌리기 어렵다),
     * 저장소 장애가 아닌 것을 여기로 넣으면 DLT 에 한 건 쌓이고 알람이 울린다(되돌리기 쉽다).
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
