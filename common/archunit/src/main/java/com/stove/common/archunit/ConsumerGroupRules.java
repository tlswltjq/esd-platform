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
 * 컨슈머 그룹 이름이 앱 안에서 한 값으로만 존재하는지 검사한다.
 *
 * <p>같은 이름이 두 곳에 산다. Kafka 쪽 {@code @KafkaListener(groupId=…)} 와
 * Inbox 멱등 키인 서비스의 {@code CONSUMER_GROUP} 상수다
 * ({@code ProcessedEventGuard}, 결정 6·8). 지금은 리스너가 서비스 상수를 참조하므로 출처가 하나지만,
 * <b>리터럴로 되돌리면 아무도 막지 않는다</b> — 애노테이션 속성은 컴파일 타임 상수라
 * 바이트코드에는 값만 남고 "무엇을 참조했는가"가 지워진다. 그래서 참조 형태가 아니라 <b>값</b>을 검사한다.
 *
 * <p><b>갈라져도 당장 깨지는 것은 없다.</b> 가드는 자기가 쓴 값과 자기가 읽는 값만 비교하므로,
 * 이름이 Kafka 그룹과 달라도 일관되기만 하면 멱등은 그대로 동작한다.
 * 이 규칙이 지키는 것은 동작이 아니라 <b>한 값으로 두기로 한 결정</b>이다(결정 16).
 * 그러니 여기서 실패하면 "고장났다"가 아니라 "한 곳으로 되돌려라"로 읽으면 된다.
 *
 * <p>Inbox 가드를 쓰지 않는 서비스(store·download — 문서 ID 고정 upsert 라 연산 자체가 멱등, 결정 8)는
 * 대조할 두 번째 출처가 없으므로 건너뛴다. 다만 <b>건너뛸 대상은 {@code ProcessedEventGuard} 주입
 * 여부로 판정한다</b> — 상수의 부재로 판정하면 "가드를 안 쓰는 앱" 과 "상수가 사라진 앱" 이 같은 문으로
 * 빠져나가, 규칙이 검사할 것을 잃고도 초록으로 보인다(D-021 과 같은 부류).
 */
public final class ConsumerGroupRules {

    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
    private static final String PROCESSED_EVENT_GUARD = "com.stove.common.messaging.inbox.ProcessedEventGuard";
    private static final String CONSUMER_GROUP_FIELD = "CONSUMER_GROUP";
    private static final String GROUP_ID = "groupId";

    private ConsumerGroupRules() {
    }

    /**
     * Kafka 컨슈머 그룹과 Inbox 멱등 키는 같은 값이다.
     *
     * <p>집합끼리 비교한다. 앱이 컨슈머 그룹을 둘 이상 쓴다면 멱등 키도 그만큼 있어야 한다 —
     * 그룹이 둘인데 키가 하나면 먼저 처리한 그룹이 나머지를 굶긴다.
     */
    @ArchTest
    static void 컨슈머_그룹과_멱등키가_같다(JavaClasses classes) {
        Set<String> kafkaGroups = kafkaGroups(classes);

        // 제외 대상은 "상수가 없는 앱" 이 아니라 "가드를 쓰지 않는 앱" 이다. 상수의 부재로 판정하면
        // 검사 대상이 사라진 것과 검사할 것이 없는 것을 구별하지 못한다 — D-021 과 같은 공허 통과다.
        if (kafkaGroups.isEmpty() || !usesInboxGuard(classes)) {
            return;
        }

        Set<String> idempotencyKeys = idempotencyKeys(classes);
        if (idempotencyKeys.isEmpty()) {
            throw new AssertionError("""
                    %s 를 쓰는데 %s 상수가 없다.
                      @KafkaListener(groupId) : %s
                    가드에 넘기는 멱등 키가 상수 밖에 있으면 이 규칙이 대조할 것을 잃는다.
                    멱등 키를 서비스의 %s (static) 로 되돌릴 것."""
                    .formatted(PROCESSED_EVENT_GUARD, CONSUMER_GROUP_FIELD, kafkaGroups, CONSUMER_GROUP_FIELD));
        }
        if (!kafkaGroups.equals(idempotencyKeys)) {
            throw new AssertionError("""
                    Kafka 컨슈머 그룹과 Inbox 멱등 키가 다르다.
                      @KafkaListener(groupId) : %s
                      %s 상수            : %s
                    둘은 한 값으로 두기로 했다(결정 16). 리스너가 서비스의 %s 를 참조하도록 되돌릴 것.
                    값을 정말 바꿔야 한다면 상수 한 곳만 고치면 양쪽이 함께 움직인다 —
                    다만 멱등 키가 바뀌면 그 그룹의 과거 처리 기록이 전부 무효가 된다."""
                    .formatted(kafkaGroups, CONSUMER_GROUP_FIELD, idempotencyKeys, CONSUMER_GROUP_FIELD));
        }
    }

    /**
     * 리스너는 컨슈머 그룹을 명시한다.
     *
     * <p>{@code application.yml} 의 {@code spring.kafka.consumer.group-id} 는 지웠다.
     * 모든 {@code @KafkaListener} 가 {@code groupId} 를 덮어쓰고 있어 한 번도 쓰이지 않으면서
     * "여기가 그룹 이름을 정하는 자리"처럼 보이던 값이라, 재처리하려는 사람이 그쪽을 고치면
     * 아무 일도 일어나지 않았다. 대신 빠뜨린 리스너를 여기서 잡는다.
     */
    @ArchTest
    static void 리스너는_컨슈머_그룹을_명시한다(JavaClasses classes) {
        Set<String> offenders = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (isKafkaListener(method) && declaredGroupId(method).isEmpty()) {
                    offenders.add(method.getFullName());
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("""
                    @KafkaListener 에 groupId 가 없다: %s
                    application.yml 의 group-id 기본값은 제거했으므로 그룹 이름은 리스너가 명시해야 한다.
                    Inbox 가드를 쓰는 서비스라면 그 서비스의 %s 를 참조할 것."""
                    .formatted(offenders, CONSUMER_GROUP_FIELD));
        }
    }

    private static Set<String> kafkaGroups(JavaClasses classes) {
        Set<String> groups = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                declaredGroupId(method).ifPresent(groups::add);
            }
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

    /** 선언된 {@code groupId}. 비어 있으면(= yml 기본값에 기대는 상태) 없는 것으로 본다. */
    private static Optional<String> declaredGroupId(JavaMethod method) {
        return method.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getName().equals(KAFKA_LISTENER))
                .findFirst()
                .flatMap(annotation -> annotation.get(GROUP_ID))
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
