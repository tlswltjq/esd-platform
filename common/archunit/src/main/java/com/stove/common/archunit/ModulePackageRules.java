package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 서비스 모듈의 패키지 구조 규약. 각 앱의 테스트에서
 * {@code ArchTests.in(ModulePackageRules.class)} 로 가져다 쓴다.
 *
 * <p>구조도와 근거는 {@code docs/decisions.md} 1·3·4·5·13번, docs/code-notes.md
 */
public final class ModulePackageRules {

    private static final String DOMAIN = "..core.domain..";
    private static final String CORE_PORT = "..core.port..";
    private static final String CORE_SERVICE = "..core.service..";
    private static final String APPLICATION = "..api.application..";
    private static final String CONTROLLER = "..api.controller..";
    private static final String LISTENER = "..api.listener..";
    private static final String SCHEDULER = "..api.scheduler..";
    private static final String INFRASTRUCTURE = "..infrastructure..";
    private static final String CONFIG = "..config..";

    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** 아웃바운드 포트 인터페이스. 구현체가 어디에 있어야 하는지를 판정하는 기준이 된다. */
    private static final DescribedPredicate<JavaClass> PORT_INTERFACE =
            DescribedPredicate.describe("아웃바운드 포트 인터페이스",
                    javaClass -> javaClass.isInterface() && javaClass.getPackageName().endsWith(".core.port"));

    private ModulePackageRules() {
    }

    /** 모든 프로덕션 클래스는 규약이 정한 패키지 중 하나에 있어야 한다. 구조 미이행을 잡는 1차 그물. */
    @ArchTest
    public static final ArchRule 클래스는_규약_패키지에_위치한다 = classes()
            .that().areTopLevelClasses()
            .should().resideInAnyPackage(
                    DOMAIN, CORE_PORT, CORE_SERVICE,
                    APPLICATION, CONTROLLER, LISTENER, SCHEDULER,
                    INFRASTRUCTURE, CONFIG)
            .orShould().haveSimpleNameEndingWith("Application")
            .because("core/api/infrastructure/config 규약 밖의 패키지는 만들지 않는다")
            .allowEmptyShould(true);

    /** 계층 간 접근 방향. 각 화살표의 근거는 docs/code-notes.md */
    @ArchTest
    public static final ArchRule 계층_접근_방향 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            .optionalLayer("Domain").definedBy(DOMAIN)
            .optionalLayer("CorePort").definedBy(CORE_PORT)
            .optionalLayer("CoreService").definedBy(CORE_SERVICE)
            .optionalLayer("Application").definedBy(APPLICATION)
            .optionalLayer("Inbound").definedBy(CONTROLLER, LISTENER, SCHEDULER)
            .optionalLayer("Infrastructure").definedBy(INFRASTRUCTURE)
            .optionalLayer("Config").definedBy(CONFIG)

            .whereLayer("Inbound").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Inbound", "Config")
            .whereLayer("CoreService").mayOnlyBeAccessedByLayers("Inbound", "Application", "Config")
            .whereLayer("CorePort").mayOnlyBeAccessedByLayers(
                    "CoreService", "Application", "Infrastructure", "Config");

    /** core 는 바깥을 모른다. 이 규칙 하나가 무너지면 나머지 구조는 의미가 없다. */
    @ArchTest
    public static final ArchRule core_는_api_와_infrastructure_를_모른다 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.stove..api..", "com.stove..infrastructure..", "com.stove..config..")
            .because("core 는 단일 도메인의 지식만 가진다 — 응답 DTO 나 어댑터를 알면 재사용 단위가 깨진다")
            .allowEmptyShould(true);

    /** core 는 전송 기술을 모른다. JPA 는 결정 3에 따라 허용 대상에서 제외한다. */
    @ArchTest
    public static final ArchRule core_는_전송_기술을_모른다 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.http..",
                    "org.springframework.kafka..",
                    "org.apache.kafka..",
                    "jakarta.servlet..")
            .because("HTTP/Kafka 는 어댑터의 관심사다")
            .allowEmptyShould(true);

    /** 아웃바운드 포트는 모듈당 {@code core.port} 한 곳에만 — 포트를 찾을 곳이 하나여야 한다. */
    @ArchTest
    public static final ArchRule 포트는_core_port_에만_정의한다 = noClasses()
            .that().resideOutsideOfPackage(CORE_PORT)
            .should().resideInAPackage("..port..")
            .allowEmptyShould(true);

    /** 포트 패키지는 인터페이스만. 값을 옆에 두면 외부 응답 형식이 계약에 눌러앉는다. */
    @ArchTest
    public static final ArchRule 포트_패키지는_인터페이스만_담는다 = classes()
            .that().resideInAPackage(CORE_PORT)
            .should().beInterfaces()
            .allowEmptyShould(true);

    /** 포트 구현체는 언제나 같은 모듈의 아웃바운드 어댑터다(결정 1). */
    @ArchTest
    public static final ArchRule 포트_구현체는_infrastructure_에_둔다 = classes()
            .that().areNotInterfaces()
            .and().areAssignableTo(PORT_INTERFACE)
            .should().resideInAPackage(INFRASTRUCTURE)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 컨트롤러는_api_controller_에_둔다 = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .or().areAnnotatedWith("org.springframework.stereotype.Controller")
            .should().resideInAPackage(CONTROLLER)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 카프카_리스너는_api_listener_에_둔다 = methods()
            .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
            .should().beDeclaredInClassesThat().resideInAPackage(LISTENER)
            .because("이벤트 수신은 컨트롤러와 동급의 인바운드 어댑터다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 스케줄러는_api_scheduler_에_둔다 = methods()
            .that().areAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
            .should().beDeclaredInClassesThat().resideInAPackage(SCHEDULER)
            .allowEmptyShould(true);

    /**
     * 설정값은 인바운드에 두지 않는다. 자리를 하나로 못 박지는 않지만({@code decisions.md} 13번)
     * {@code api} 만은 아니다 — 진입점마다 값이 갈린다.
     */
    @ArchTest
    public static final ArchRule 설정값은_인바운드에_두지_않는다 = noClasses()
            .that().areAnnotatedWith(CONFIGURATION_PROPERTIES)
            .should().resideInAnyPackage(CONTROLLER, LISTENER, SCHEDULER)
            .because("설정은 진입점이 아니라 그 값을 쓰는 계층이 소유한다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 엔티티는_core_domain_에_둔다 = classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAPackage(DOMAIN)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 스프링_데이터_리포지토리는_core_domain_에_둔다 = classes()
            .that().areInterfaces()
            .and().areAssignableTo("org.springframework.data.repository.Repository")
            .should().resideInAPackage(DOMAIN)
            .allowEmptyShould(true);
}
