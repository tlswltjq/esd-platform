package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 컨슈머 층의 순서 보장을 떠받치는 전제를 코드로 고정한다.
 *
 * <p>README 의 "같은 애그리거트의 순서가 보장된다"는 문장은 세 층이 전부 제 몫을 해야 성립한다
 * (프로듀서 · 발행자 · 컨슈머). 앞의 두 층은 설정과 릴레이 코드가 지키고 테스트도 있다.
 * <b>컨슈머 층만 "지금 구성이 우연히 안전한" 상태였다</b> —
 * 리스너가 단일 스레드로 받은 순서대로 처리하기 때문인데, 그걸 강제하는 것이 아무것도 없었다.
 *
 * <p>여기서 막는 둘은 공통점이 있다. <b>도입하는 순간 순서가 깨지지만, 깨진 티가 안 난다.</b>
 * 처리량이 필요해서 비동기로 넘기거나 재시도를 논블로킹으로 바꾸는 것은 둘 다 자연스러운 선택이라,
 * 리뷰에서 "이러면 순서가 깨진다"는 지적이 나오지 않으면 그대로 들어간다.
 *
 * <p>배경과 대안은 {@code docs/event-ordering.md} 5절.
 */
public final class EventOrderingRules {

    private static final String LISTENER = "..api.listener..";

    private static final String ASYNC = "org.springframework.scheduling.annotation.Async";
    private static final String RETRYABLE_TOPIC = "org.springframework.kafka.annotation.RetryableTopic";
    private static final String EXECUTOR = "java.util.concurrent.Executor";
    private static final String EXECUTOR_SERVICE = "java.util.concurrent.ExecutorService";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";

    private EventOrderingRules() {
    }

    /**
     * 리스너는 받은 스레드에서 그대로 처리한다.
     *
     * <p>스레드풀에 넘기는 순간 처리 순서가 도착 순서와 무관해진다. 같은 파티션에서
     * 순서대로 꺼내 와도 소용이 없다 — 커밋(`ack-mode: record`)도 처리 완료보다 먼저 일어나
     * 실패한 이벤트가 조용히 사라진다.
     *
     * <p>처리량이 필요하면 {@code concurrency} 를 올린다. 파티션 수까지는 안전하다 —
     * 스레드마다 파티션을 하나씩 맡으므로 같은 키는 여전히 한 스레드가 순서대로 처리한다.
     */
    @ArchTest
    public static final ArchRule 리스너는_다른_스레드로_넘기지_않는다 = noClasses()
            .that().resideInAPackage(LISTENER)
            .should().dependOnClassesThat().haveFullyQualifiedName(EXECUTOR)
            .orShould().dependOnClassesThat().haveFullyQualifiedName(EXECUTOR_SERVICE)
            .orShould().dependOnClassesThat().haveFullyQualifiedName(COMPLETABLE_FUTURE)
            .because("리스너 안에서 비동기로 넘기면 처리 순서가 도착 순서와 무관해진다 "
                    + "(docs/event-ordering.md 5절). 처리량은 concurrency 로 올린다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 리스너_메서드에_Async_를_붙이지_않는다 = methods()
            .that().areDeclaredInClassesThat().resideInAPackage(LISTENER)
            .should().notBeAnnotatedWith(ASYNC)
            .because("@Async 는 리스너 스레드와 처리 스레드를 갈라 놓아 순서 보장을 무효화한다")
            .allowEmptyShould(true);

    /**
     * 논블로킹 재시도를 쓰지 않는다.
     *
     * <p>{@code @RetryableTopic} 은 실패한 메시지를 재시도 토픽으로 빼내 파티션을 계속 진행시킨다.
     * 파티션이 멈추지 않는다는 것이 장점이지만, <b>실패한 메시지가 뒤로 밀린다</b>는 뜻이라
     * 같은 키의 후속 메시지가 그 앞을 지나간다. 발행 측에서 D-013/D-014 로 막은 추월을
     * 수신 측에서 다시 열게 된다.
     *
     * <p>현재는 블로킹 재시도({@code DefaultErrorHandler})를 쓴다. 이유는
     * {@code docs/kafka-consumer-retry.md} 6절.
     */
    @ArchTest
    public static final ArchRule 논블로킹_재시도를_쓰지_않는다 = noMethods()
            .should().beAnnotatedWith(RETRYABLE_TOPIC)
            .because("@RetryableTopic 은 실패 메시지를 뒤로 미뤄 같은 키의 순서를 깬다 "
                    + "(docs/kafka-consumer-retry.md 6절)")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 클래스에도_논블로킹_재시도를_쓰지_않는다 = noClasses()
            .should().beAnnotatedWith(RETRYABLE_TOPIC)
            .allowEmptyShould(true);
}
