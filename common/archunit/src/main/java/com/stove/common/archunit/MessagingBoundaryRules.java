package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 메시징 경계(결정 209). 브로커는 adapter 모듈만 안다 — 업무 코드와 메시징 코어는 포트(common:event)와
 * 계약(common:contract)만 쓴다. 그래야 같은 산출물에서 {@code stove.messaging.broker} 만 바꿔 네 기술을 비교할 수 있다.
 *
 * <p>Gradle 도 막는다: 앱은 adapter 를 {@code runtimeOnly} 로만 넣고, {@code verifyCommonRuntimeDependencies} 가
 * 코어·계약·포트의 런타임 클래스패스에 브로커 클라이언트가 없는지 본다. 이 규칙은 그 의도를 코드 쪽에서 한 번 더 적는다.
 */
public final class MessagingBoundaryRules {

    /** 메시징 전용 클라이언트 패키지. 업무 코드는 이것을 쓰지 않는다. */
    static final String[] MESSAGING_CLIENTS = {
            "org.apache.kafka..", "org.springframework.kafka..",
            "org.springframework.amqp..", "com.rabbitmq.."};

    /**
     * 메시징 코어·계약이 모르는 브로커 클라이언트 전체. Redis 클라이언트는 메시징 밖에서도 쓰이므로
     * (Gateway BFF 세션 토큰) 앱 규칙에서는 빼고 코어·계약에서만 막는다.
     */
    static final String[] BROKER_CLIENTS = {
            "org.apache.kafka..", "org.springframework.kafka..",
            "org.springframework.amqp..", "com.rabbitmq..",
            "io.lettuce..", "org.springframework.data.redis.."};

    /** 메시징 adapter 패키지. */
    static final String[] ADAPTERS = {
            "com.stove.common.messaging.kafka..", "com.stove.common.messaging.rabbitmq..",
            "com.stove.common.messaging.redis..", "com.stove.common.messaging.direct.."};

    private MessagingBoundaryRules() {
    }

    @ArchTest
    public static final ArchRule 앱은_브로커를_모른다 = noClasses()
            .that().resideInAPackage("com.stove..")
            .and().resideOutsideOfPackage("com.stove.common..")
            .should().dependOnClassesThat().resideInAnyPackage(MESSAGING_CLIENTS)
            .orShould().dependOnClassesThat().resideInAnyPackage(ADAPTERS)
            .because("브로커를 바꿔 비교하려면 업무 코드가 메시징 기술을 몰라야 한다(결정 209). Redis 를 캐시·세션에 쓰는 것은 막지 않는다 — Redis Streams 는 adapter 패키지로 막는다")
            .allowEmptyShould(true);
}
