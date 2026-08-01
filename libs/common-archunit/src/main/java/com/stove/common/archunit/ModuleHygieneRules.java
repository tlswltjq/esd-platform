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

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";

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
