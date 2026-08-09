package com.stove.common.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * DLT 발행자를 만드는 유일한 자리. <b>보내는 것과 세는 것을 함께 묶는다.</b>
 *
 * <p>이 클래스가 생긴 이유는 실제로 두 번 갈렸기 때문이다.
 *
 * <ol>
 *   <li><b>이름이 갈렸다.</b> 기본 에러 핸들러는 {@link DeadLetterTopics} 규칙으로 보냈는데,
 *       자기 핸들러를 가진 license 는 {@code new DeadLetterPublishingRecoverer(template)} 을
 *       직접 만들어 스프링 기본 접미사({@code -dlt})로 보내고 있었다. 원격 스택에서 같은 흐름의
 *       DLT 토픽이 두 개 생기는 것으로 드러났다. 이름이 갈리면 재투입이 "그런 토픽이 없다"로
 *       조용히 실패하고 알람도 절반만 맞는다.</li>
 *   <li><b>계측이 갈렸다.</b> 이름을 고친 뒤에도 license 의 DLT 유입만 지표에 안 잡혔다 —
 *       카운터를 에러 핸들러 쪽에서 올리고 있어서, 자기 recoverer 로 직접 보내는 경로가 비껴갔다.
 *       <b>알람이 조용한 것과 지표가 없는 것은 다르다.</b></li>
 * </ol>
 *
 * <p>그래서 발행과 계측을 한 덩어리로 묶어 내보낸다. 잊을 수 있는 조합을 남기지 않는 것이 요점이다.
 * 앱이 {@code DeadLetterPublishingRecoverer} 를 직접 만드는 것은
 * {@code 앱은_DLT_발행자를_직접_만들지_않는다} 규칙이 막는다.
 */
public final class DeadLetterPublisher {

    private DeadLetterPublisher() {
    }

    /**
     * 재시도를 소진한 레코드를 {@code <원본토픽>.DLT} 의 같은 파티션으로 보내고, 그 사실을 센다.
     *
     * <p>세는 것은 <b>발행이 성공한 뒤</b>다. 브로커가 죽어 발행이 실패하면 예외가 나가고
     * 카운터는 오르지 않는다 — "DLT 로 옮겼다"는 지표가 실제로 옮겨졌을 때만 오르게 하기 위함이다.
     */
    public static ConsumerRecordRecoverer to(KafkaTemplate<String, String> kafkaTemplate,
                                             DeadLetterMetrics metrics) {
        ConsumerRecordRecoverer publisher =
                new DeadLetterPublishingRecoverer(kafkaTemplate, DeadLetterTopics::of);
        return (record, exception) -> {
            publisher.accept(record, exception);
            metrics.recordDeadLettered(record.topic());
        };
    }
}
