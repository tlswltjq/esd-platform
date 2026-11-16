package com.stove.common.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 컨슈머 그룹 이름이 앱 안에서 한 값으로만 존재하는지 검사한다(결정 16).
 * 참조 형태가 아니라 <b>값</b>을 검사한다 — 애노테이션 속성은 컴파일 타임 상수라
 * 바이트코드에 "무엇을 참조했는가"가 남지 않는다.
 *
 * <p>여기서 실패하면 "고장났다"가 아니라 "한 곳으로 되돌려라"로 읽는다.
 */
public final class ConsumerGroupRules {

    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    private static final String EVENT_SUBSCRIBER = "com.stove.common.event.EventSubscriber";
    private static final String GROUP = "group";
    private static final String PROCESSED_EVENT_GUARD = "com.stove.common.messaging.inbox.ProcessedEventGuard";
    private static final String CONSUMER_GROUP_FIELD = "CONSUMER_GROUP";

    private ConsumerGroupRules() {
    }

    /** 소비자 그룹({@code @EventSubscriber(group)})과 Inbox 멱등 키는 같은 값이다. 집합끼리 비교한다. */
    @ArchTest
    static void 컨슈머_그룹과_멱등키가_같다(JavaClasses classes) {
        Set<String> subscriberGroups = subscriberGroups(classes);

        // 제외 대상은 "상수가 없는 앱" 이 아니라 "가드를 쓰지 않는 앱" 이다 — 공허 통과 방지. [D-021]
        if (subscriberGroups.isEmpty() || !usesInboxGuard(classes)) {
            return;
        }

        Set<String> idempotencyKeys = idempotencyKeys(classes);
        if (idempotencyKeys.isEmpty()) {
            throw new AssertionError("""
                    %s 를 쓰는데 %s 상수가 없다.
                      @EventSubscriber(group) : %s
                    가드에 넘기는 멱등 키가 상수 밖에 있으면 이 규칙이 대조할 것을 잃는다.
                    멱등 키를 서비스의 %s (static) 로 되돌릴 것."""
                    .formatted(PROCESSED_EVENT_GUARD, CONSUMER_GROUP_FIELD, subscriberGroups, CONSUMER_GROUP_FIELD));
        }
        if (!subscriberGroups.equals(idempotencyKeys)) {
            throw new AssertionError("""
                    소비자 그룹과 Inbox 멱등 키가 다르다.
                      @EventSubscriber(group) : %s
                      %s 상수            : %s
                    둘은 한 값으로 두기로 했다(결정 16). 리스너가 서비스의 %s 를 참조하도록 되돌릴 것.
                    값을 정말 바꿔야 한다면 상수 한 곳만 고치면 양쪽이 함께 움직인다 —
                    다만 멱등 키가 바뀌면 그 그룹의 과거 처리 기록이 전부 무효가 된다."""
                    .formatted(subscriberGroups, CONSUMER_GROUP_FIELD, idempotencyKeys, CONSUMER_GROUP_FIELD));
        }
    }

    /**
     * 리스너는 컨슈머 그룹을 명시한다.
     *
     * <p>{@code application.yml} 의 {@code spring.kafka.consumer.group-id} 는 지웠다.
     * 모든 리스너가 그룹을 덮어쓰고 있어 한 번도 쓰이지 않으면서
     * "여기가 그룹 이름을 정하는 자리"처럼 보이던 값이라, 재처리하려는 사람이 그쪽을 고치면
     * 아무 일도 일어나지 않았다. 대신 빈 그룹을 단 리스너를 여기서 잡는다.
     */
    @ArchTest
    static void 리스너는_컨슈머_그룹을_명시한다(JavaClasses classes) {
        Set<String> offenders = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (isEventSubscriber(javaClass) && declaredGroup(javaClass).isEmpty()) {
                offenders.add(javaClass.getName());
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("""
                    @EventSubscriber 의 group 이 비어 있다: %s
                    application.yml 의 group-id 기본값은 제거했으므로 그룹 이름은 리스너가 명시해야 한다.
                    Inbox 가드를 쓰는 서비스라면 그 서비스의 %s 를 참조할 것."""
                    .formatted(offenders, CONSUMER_GROUP_FIELD));
        }
    }

    /**
     * 업무 리스너는 브로커 리스너({@code @KafkaListener})를 직접 달지 않는다(결정 202).
     *
     * <p>{@code @EventSubscriber} + {@code EventHandler} 로 선언하면 adapter(common:kafka)가 구독을 만든다.
     * 직접 달면 그 리스너만 브로커를 바꿀 때 따라오지 않고, 위 그룹 대조에서도 빠진다.
     */
    @ArchTest
    static void 업무_리스너는_브로커_리스너를_직접_달지_않는다(JavaClasses classes) {
        Set<String> offenders = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (isKafkaListener(method)) {
                    offenders.add(method.getFullName());
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("""
                    @KafkaListener 를 직접 달았다: %s
                    클래스에 @EventSubscriber(topics, group) 를 달고 EventHandler#receive(InboundMessage) 로 받을 것."""
                    .formatted(offenders));
        }
    }

    private static Set<String> subscriberGroups(JavaClasses classes) {
        Set<String> groups = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            declaredGroup(javaClass).ifPresent(groups::add);
        }
        return groups;
    }

    private static Set<String> idempotencyKeys(JavaClasses classes) {
        Set<String> keys = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaField field : javaClass.getFields()) {
                if (field.getName().equals(CONSUMER_GROUP_FIELD)
                        && field.getModifiers().contains(JavaModifier.STATIC)) {
                    keys.add(constantValue(javaClass, field));
                }
            }
        }
        return keys;
    }

    /**
     * 이 앱이 Inbox 가드를 쓰는가 — {@code ProcessedEventGuard} 를 주입받는 클래스가 있는가.
     *
     * <p>store·download 를 이름으로 적어두지 않는 이유가 여기 있다. 목록은 낡는다.
     * 가드를 새로 쓰기 시작한 앱을 목록에 넣는 것을 잊으면 그 앱은 영영 검사되지 않고,
     * 그 사실이 초록으로 보인다. 가드 사용 여부는 코드에 이미 적혀 있으므로 거기서 읽는다.
     */
    private static boolean usesInboxGuard(JavaClasses classes) {
        for (JavaClass javaClass : classes) {
            for (JavaField field : javaClass.getFields()) {
                if (field.getRawType().getName().equals(PROCESSED_EVENT_GUARD)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isKafkaListener(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getRawType().getName().equals(KAFKA_LISTENER));
    }

    private static boolean isEventSubscriber(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(EVENT_SUBSCRIBER);
    }

    /** 선언된 {@code group}. 비어 있으면 없는 것으로 본다. */
    private static Optional<String> declaredGroup(JavaClass javaClass) {
        if (!isEventSubscriber(javaClass)) {
            return Optional.empty();
        }
        return javaClass.getAnnotationOfType(EVENT_SUBSCRIBER).get(GROUP)
                .map(String::valueOf)
                .filter(value -> !value.isBlank());
    }

    private static String constantValue(JavaClass owner, JavaField field) {
        try {
            Field reflected = owner.reflect().getDeclaredField(field.getName());
            reflected.setAccessible(true);
            return String.valueOf(reflected.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "%s.%s 값을 읽을 수 없다".formatted(owner.getName(), field.getName()), e);
        }
    }
}
