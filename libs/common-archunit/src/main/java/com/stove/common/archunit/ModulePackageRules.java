package com.stove.common.archunit;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 서비스 모듈의 패키지 구조 규약.
 *
 * <pre>
 * com.stove.&lt;app&gt;
 * ├── &lt;App&gt;Application
 * ├── config/                 스프링 설정, @ConfigurationProperties
 * ├── core/                   단일 도메인. 이 모듈의 존재 이유.
 * │   ├── domain/             엔티티(JPA 허용), VO, enum, 정책, Repository 인터페이스
 * │   ├── port/               core 가 필요로 하는 아웃바운드 포트 (PG, CDN, 스토리지 …)
 * │   └── service/            단일 애그리거트 동작 + 트랜잭션 경계
 * ├── api/                    바깥 세계와의 접점
 * │   ├── controller/         HTTP 인바운드 — 엔드포인트, 요청/응답 DTO
 * │   ├── listener/           Kafka 인바운드
 * │   ├── scheduler/          배치/스케줄 인바운드
 * │   └── application/        조율이 필요한 경우의 파사드 + port/ (외부 도메인 포트)
 * └── infrastructure/         모든 아웃바운드 포트 구현체
 * </pre>
 *
 * <p>확정된 설계 결정 3가지가 규칙에 반영되어 있다.
 * <ol>
 *   <li>포트 구현체는 같은 모듈의 {@code infrastructure} 어댑터다. 앱 간 컴파일 의존은 없다.</li>
 *   <li>파사드는 강제하지 않는다 — 인바운드가 {@code core.service} 를 직접 호출해도 된다.</li>
 *   <li>도메인 객체와 엔티티를 분리하지 않는다 — {@code core.domain} 의 JPA 의존을 허용한다.</li>
 * </ol>
 *
 * <p>각 앱의 테스트에서 {@code ArchTests.in(ModulePackageRules.class)} 로 가져다 쓴다.
 */
public final class ModulePackageRules {

    private static final String DOMAIN = "..core.domain..";
    private static final String CORE_PORT = "..core.port..";
    private static final String CORE_SERVICE = "..core.service..";
    private static final String APPLICATION = "..api.application..";
    private static final String APP_PORT = "..api.application.port..";
    private static final String CONTROLLER = "..api.controller..";
    private static final String LISTENER = "..api.listener..";
    private static final String SCHEDULER = "..api.scheduler..";
    private static final String INFRASTRUCTURE = "..infrastructure..";
    private static final String CONFIG = "..config..";

    /** 아웃바운드 포트 인터페이스. 구현체가 어디에 있어야 하는지를 판정하는 기준이 된다. */
    private static final DescribedPredicate<JavaClass> PORT_INTERFACE =
            DescribedPredicate.describe("아웃바운드 포트 인터페이스", javaClass -> javaClass.isInterface()
                    && (javaClass.getPackageName().endsWith(".core.port")
                            || javaClass.getPackageName().endsWith(".api.application.port")));

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

    /**
     * 계층 간 접근 방향.
     *
     * <p>파사드를 강제하지 않으므로 인바운드는 {@code application} 과 {@code core.service} 를 모두 호출할 수 있다.
     * 반대로 {@code infrastructure}(포트 구현체)는 DI 로만 주입되어야 하므로 아무도 직접 참조하지 못한다.
     *
     * <p>포트 패키지는 자기 계층에서 떼어내 별도 레이어로 둔다. 어댑터는 자신이 구현하는 포트를
     * 반드시 참조해야 하지만, 그렇다고 파사드나 서비스 본체까지 볼 수 있어서는 안 되기 때문이다.
     */
    @ArchTest
    public static final ArchRule 계층_접근_방향 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            .optionalLayer("Domain").definedBy(DOMAIN)
            .optionalLayer("CorePort").definedBy(CORE_PORT)
            .optionalLayer("CoreService").definedBy(CORE_SERVICE)
            .optionalLayer("AppPort").definedBy(APP_PORT)
            .optionalLayer("Application")
            .definedBy(resideInAPackage(APPLICATION).and(not(resideInAPackage(APP_PORT))))
            .optionalLayer("Inbound").definedBy(CONTROLLER, LISTENER, SCHEDULER)
            .optionalLayer("Infrastructure").definedBy(INFRASTRUCTURE)
            .optionalLayer("Config").definedBy(CONFIG)

            .whereLayer("Inbound").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Inbound", "Config")
            .whereLayer("AppPort").mayOnlyBeAccessedByLayers(
                    "Application", "Inbound", "Infrastructure", "Config")
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

    /**
     * 포트 패키지는 계약만 담는다 — 인터페이스와 그 인터페이스가 주고받는 값 타입.
     * 구현 클래스가 여기 들어오면 어댑터가 계약 안으로 새어 들어온 것이다.
     */
    @ArchTest
    public static final ArchRule 포트_패키지는_계약만_담는다 = classes()
            .that().resideInAnyPackage(CORE_PORT, APP_PORT)
            .should().beInterfaces()
            .orShould().beRecords()
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
