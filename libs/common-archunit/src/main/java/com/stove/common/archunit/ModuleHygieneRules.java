package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 패키지 배치 다음 단계의 규칙 — 트랜잭션 경계, 네이밍, 순환, 일반 위생.
 *
 * <p>{@link ModulePackageRules} 가 "어디에 두는가"라면 이쪽은 "무엇을 두는가"를 다룬다.
 * 배치가 정리된 모듈부터 순차적으로 붙여 나간다.
 */
public final class ModuleHygieneRules {

    private static final String CORE_SERVICE = "..core.service..";
    private static final String APPLICATION = "..api.application..";
    private static final String CONTROLLER = "..api.controller..";
    private static final String LISTENER = "..api.listener..";
    private static final String SCHEDULER = "..api.scheduler..";
    private static final String INFRASTRUCTURE = "..infrastructure..";

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String PROCESSED_EVENT_GUARD = "com.stove.common.messaging.inbox.ProcessedEventGuard";
    private static final String PROFILE = "org.springframework.context.annotation.Profile";
    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String CONDITIONAL_ON_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    private ModuleHygieneRules() {
    }

    /**
     * 트랜잭션 경계는 {@code core.service} 한 곳이다.
     *
     * <p>인바운드 어댑터가 트랜잭션을 열면 외부 호출이 트랜잭션 안으로 들어오고,
     * 파사드가 열면 단일 도메인 서비스가 자기 경계를 잃는다.
     */
    @ArchTest
    public static final ArchRule 트랜잭션_경계는_core_service_다 = methods()
            .that().areAnnotatedWith(TRANSACTIONAL)
            .should().beDeclaredInClassesThat().resideInAPackage(CORE_SERVICE)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 클래스_레벨_트랜잭션도_core_service_다 = classes()
            .that().areAnnotatedWith(TRANSACTIONAL)
            .should().resideInAPackage(CORE_SERVICE)
            .allowEmptyShould(true);

    /** 인바운드 어댑터는 영속성에 직접 닿지 않는다. */
    @ArchTest
    public static final ArchRule 인바운드는_리포지토리를_직접_쓰지_않는다 = noClasses()
            .that().resideInAnyPackage(CONTROLLER, LISTENER, SCHEDULER)
            .should().dependOnClassesThat().areAssignableTo(SPRING_DATA_REPOSITORY)
            .allowEmptyShould(true);

    /**
     * 멱등 가드는 서비스가 소유한다(결정 6).
     *
     * <p>트랜잭션 위치만 막아서는 부족하다. 리스너가 가드를 직접 부르면 트랜잭션은 서비스에 있어도
     * "이 메시지를 이미 처리했는가"의 판정만 어댑터로 새어 나온다. 그러면 어댑터를 갈아끼울 때
     * (Kafka → SQS, 운영툴 재처리) 멱등성이 따라오지 않고, Kafka 없이 서비스만으로 멱등성을
     * 테스트할 수도 없다. 리스너는 역직렬화와 위임만 한다.
     */
    @ArchTest
    public static final ArchRule 멱등_가드는_서비스가_소유한다 = noClasses()
            .that().resideInAnyPackage(CONTROLLER, LISTENER, SCHEDULER)
            .should().dependOnClassesThat().haveFullyQualifiedName(PROCESSED_EVENT_GUARD)
            .because("리스너가 가드를 부르면 어댑터를 갈아끼울 때 멱등성이 따라오지 않는다")
            .allowEmptyShould(true);

    /**
     * 스텁 어댑터는 프로파일이나 조건으로 격리한다(결정 9).
     *
     * <p>무조건적인 {@code @Component} 인 스텁은 실제 어댑터를 붙이는 순간 같은 포트에 빈이 둘이 되어
     * 기동을 깨뜨린다. 더 나쁜 경우는 운영에서 스텁이 조용히 도는 것이다.
     * {@code prod} 에서 구현이 없으면 "no qualifying bean" 으로 즉시 실패하는 편이 낫다.
     */
    @ArchTest
    public static final ArchRule 스텁_어댑터는_격리한다 = classes()
            .that().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Mock")
            .or().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Stub")
            .or().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Fake")
            .should().beAnnotatedWith(PROFILE)
            .orShould().beAnnotatedWith(CONDITIONAL_ON_PROPERTY)
            .because("스텁이 조건 없이 빈으로 뜨면 운영에서 조용히 돌거나 실제 어댑터와 충돌한다")
            .allowEmptyShould(true);

    /** {@code core.service} 와 {@code api.application} 을 이름으로 구분한다. */
    @ArchTest
    public static final ArchRule core_서비스_네이밍 = classes()
            .that().resideInAPackage(CORE_SERVICE)
            .should().haveSimpleNameEndingWith("Service")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 파사드_네이밍 = classes()
            .that().resideInAPackage(APPLICATION)
            .and().areAnnotatedWith("org.springframework.stereotype.Service")
            .should().haveSimpleNameEndingWith("Facade")
            .because("core.service 와 조율 계층을 이름만으로 구분할 수 있어야 한다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 컨트롤러_네이밍 = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 리스너_네이밍 = classes()
            .that().resideInAPackage(LISTENER)
            .should().haveSimpleNameEndingWith("Listener")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 설정값_네이밍 = classes()
            .that().areAnnotatedWith(CONFIGURATION_PROPERTIES)
            .should().haveSimpleNameEndingWith("Properties")
            .allowEmptyShould(true);

    /**
     * 설정값은 불변이다.
     *
     * <p>setter 가 있는 설정 클래스는 사실상 전역 가변 상태다. 아무나 런타임에 바꿀 수 있고,
     * 바뀐 시점이 로그에도 안 남는다. record 로 두면 바인딩 시점 이후로 값이 고정되고
     * 기본값 처리도 생성자 한 곳에 모인다 — 지금 7개가 전부 그렇게 되어 있다.
     */
    @ArchTest
    public static final ArchRule 설정값은_불변이다 = classes()
            .that().areAnnotatedWith(CONFIGURATION_PROPERTIES)
            .should().beRecords()
            .because("setter 가 달린 설정은 런타임에 조용히 바뀌는 전역 가변 상태다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 리포지토리_네이밍 = classes()
            .that().areInterfaces()
            .and().areAssignableTo(SPRING_DATA_REPOSITORY)
            .should().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    /**
     * core / api / infrastructure / config 사이에 순환이 없어야 한다.
     *
     * <p>슬라이스가 하나도 없는 모듈(gateway 처럼 클래스가 애플리케이션 하나뿐인 경우)에서도
     * 규칙 세트를 그대로 쓸 수 있도록 빈 평가를 허용한다. 다른 규칙들과 같은 이유다.
     */
    @ArchTest
    public static final ArchRule 최상위_패키지_순환_없음 = slices()
            .matching("com.stove.*.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    /** 필드가 하나도 없는 모듈에서도 평가할 수 있어야 한다 — {@code noFields()} 는 빈 입력에서 실패한다. */
    @ArchTest
    public static final ArchRule 필드_주입_금지 = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 표준_출력_금지 = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 제네릭_예외_금지 = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.allowEmptyShould(true);
}
