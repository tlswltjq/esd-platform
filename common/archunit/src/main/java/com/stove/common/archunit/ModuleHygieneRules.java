package com.stove.common.archunit;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
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
 * {@link ModulePackageRules} 가 "어디에 두는가"라면 이쪽은 "무엇을 두는가"다. docs/code-notes.md
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
    private static final String DLT_RECOVERER =
            "org.springframework.kafka.listener.DeadLetterPublishingRecoverer";

    private ModuleHygieneRules() {
    }

    /** DLT 발행자는 {@code DeadLetterPublisher} 로만. <b>실제로 갈렸던 자리다.</b> docs/code-notes.md */
    @ArchTest
    public static final ArchRule 앱은_DLT_발행자를_직접_만들지_않는다 = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName(DLT_RECOVERER)
            .because("DLT 이름 규칙이 갈리면 재투입이 조용히 실패한다 — DeadLetterPublisher 를 쓴다")
            .allowEmptyShould(true);

    /** 트랜잭션 경계는 {@code core.service} 한 곳이다. docs/code-notes.md */
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

    /** 멱등 가드는 서비스가 소유한다(결정 6). 리스너는 역직렬화와 위임만 한다. */
    @ArchTest
    public static final ArchRule 멱등_가드는_서비스가_소유한다 = noClasses()
            .that().resideInAnyPackage(CONTROLLER, LISTENER, SCHEDULER)
            .should().dependOnClassesThat().haveFullyQualifiedName(PROCESSED_EVENT_GUARD)
            .because("리스너가 가드를 부르면 어댑터를 갈아끼울 때 멱등성이 따라오지 않는다")
            .allowEmptyShould(true);

    /** 스텁 어댑터는 프로파일이나 조건으로 격리한다(결정 9). docs/code-notes.md */
    @ArchTest
    public static final ArchRule 스텁_어댑터는_격리한다 = classes()
            // 술어를 명시적으로 묶는다 — 유창한 .and()/.or() 는 우선순위 없이 왼쪽부터
            // 결합해 검사 대상이 조용히 줄어든다. [D-021]
            .that(resideInAPackage(INFRASTRUCTURE)
                    .and(simpleNameStartingWith("Mock")
                            .or(simpleNameStartingWith("Stub"))
                            .or(simpleNameStartingWith("Fake"))))
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

    /** 설정값은 불변이다. setter 가 달린 설정은 런타임에 조용히 바뀌는 전역 가변 상태다. */
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

    /** core / api / infrastructure / config 사이에 순환이 없어야 한다. */
    @ArchTest
    public static final ArchRule 최상위_패키지_순환_없음 = slices()
            .matching("com.stove.*.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    /** {@code noFields()} 는 빈 입력에서 실패하므로 빈 평가를 허용한다. */
    @ArchTest
    public static final ArchRule 필드_주입_금지 = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 표준_출력_금지 = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 제네릭_예외_금지 = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.allowEmptyShould(true);
}
