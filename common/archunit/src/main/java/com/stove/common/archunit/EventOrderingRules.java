package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 컨슈머 층의 순서 보장을 떠받치는 전제를 코드로 고정한다.
 * 여기서 막는 둘은 <b>도입하는 순간 순서가 깨지지만 깨진 티가 안 난다.</b>
 * docs/code-notes.md, {@code docs/event-ordering.md} 5절.
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
     * <b>처리량이 필요하면 {@code concurrency} 를 올린다</b>(파티션 수까지 안전).
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
     * 논블로킹 재시도를 쓰지 않는다 — 발행 측에서 D-013/D-014 로 막은 추월을 수신 측에서 다시 연다.
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
