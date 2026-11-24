package com.stove.common.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 {@link EventHandler} 가 어느 토픽을 어느 소비자 그룹으로 받는지 선언한다.
 *
 * <p>값은 컴파일 상수여야 한다. ArchUnit({@code ConsumerGroupRules})이 바이트코드에서 {@link #group()} 을 읽어
 * Inbox 멱등 키({@code CONSUMER_GROUP})와 같은지 확인한다(결정 16).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventSubscriber {

    /** 받을 업무 토픽({@code Topics}). 여러 개면 키 순서는 토픽마다 따로 지켜진다. */
    String[] topics();

    /** 소비자 그룹. Inbox 멱등 키와 같은 값이다. 그룹마다 모든 메시지를 한 번씩 받는다(팬아웃). */
    String group();

    /** 재시도해도 결과가 같은 예외. 던지면 재시도 없이 바로 격리한다. */
    Class<? extends RuntimeException>[] noRetryFor() default {};

    /**
     * 한 번에 한 건만 가져온다. 한 건 처리에 외부 HTTP 대기처럼 오래 걸리는 리스너가 쓴다
     * — 여러 건을 한꺼번에 가져오면 뒤 건들이 브로커의 처리 기한을 넘긴다.
     */
    boolean oneAtATime() default false;

    /**
     * 일시 실패를 격리하지 않고 성공할 때까지 제자리 재시도한다(그동안 그 파티션이 멈춘다). {@link #noRetryFor()} 예외(영구
     * 실패)만 격리로 간다. 멱등이고 순서에 무관하며 빠지면 안 되는 사실을 DB 없는 모듈이 받을 때만 쓴다(결정 226: 웹 BFF·API
     * 게이트웨이의 계정 접근 변경). 소비 지연 게이지로 멈춤을 감지해야 한다. Kafka adapter만 지원한다.
     */
    boolean retryUntilSuccess() default false;
}
