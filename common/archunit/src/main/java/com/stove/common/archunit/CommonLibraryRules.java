package com.stove.common.archunit;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * {@code common/*} 에 적용하는 규칙. 라이브러리에서 지켜야 하는 것은 배치가 아니라 <b>방향</b>이다.
 *
 */
public final class CommonLibraryRules {

    private static final String COMMON = "com.stove.common..";

    /** {@code com.stove} 아래이면서 공용이 아닌 것 = 서비스 모듈. */
    private static final DescribedPredicate<JavaClass> APP_CLASS =
            DescribedPredicate.describe("서비스 모듈의 클래스",
                    javaClass -> resideInAPackage("com.stove..").test(javaClass)
                            && !resideInAPackage(COMMON).test(javaClass));

    private CommonLibraryRules() {
    }

    @ArchTest
    public static final ArchRule 공통_설정_바인딩은_config_에_둔다 = classes()
            .that().areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
            .should().resideInAPackage("..config..")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 공통_실행_객체는_바인딩_객체를_모른다 = noClasses()
            .that(DescribedPredicate.describe("설정 조립 경계 밖의 클래스", type ->
                    !type.getPackageName().contains(".config") && !type.getSimpleName().endsWith("AutoConfiguration")))
            .should().dependOnClassesThat().areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
            .because("자동 구성은 값을 추출해 전달하고 릴레이 등 실행 객체는 바인딩 타입을 모른다")
            .allowEmptyShould(true);

    /** 공용 라이브러리는 자신을 쓰는 앱을 모른다. 방향이 뒤집히면 재사용 단위가 사라진다. */
    @ArchTest
    public static final ArchRule 공용_라이브러리는_앱을_모른다 = noClasses()
            .that().resideInAPackage(COMMON)
            .should().dependOnClassesThat(APP_CLASS)
            .because("공용 코드가 특정 서비스를 알면 그 서비스의 일부이지 라이브러리가 아니다")
            .allowEmptyShould(true);

    /**
     * 이벤트 계약은 영속성을 모른다.
     *
     * <p>{@code common:contract} 의 의존은 Jackson 과 Kafka 클라이언트(레코드·헤더 타입)까지다 —
     * 봉투 규약은 포함하되 JPA 는 포함하지 않는다(결정 227).
     * 페이로드에 엔티티가 섞이면 이벤트가 발행 측 스키마에 묶인다.
     */
    @ArchTest
    public static final ArchRule 이벤트_계약은_영속성을_모른다 = noClasses()
            .that().resideInAPackage("com.stove.common.contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..",
                    "org.springframework.data..")
            .because("이벤트 페이로드는 저장 방식과 독립이다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 인증과_영속성_지원은_공통_웹을_모른다 = noClasses()
            .that().resideInAnyPackage("com.stove.common.security..", "com.stove.common.jpa..")
            .should().dependOnClassesThat().resideInAPackage("com.stove.common.web..")
            .because("인증·영속성 지원은 HTTP 응답 계약을 실행 의존성으로 요구하지 않는다")
            .allowEmptyShould(true);

    /** 메시징 코어 = {@code com.stove.common.messaging} 아래에서 Kafka 공통 모듈 패키지를 뺀 것(Outbox·Inbox·격리, JPA). */
    private static final DescribedPredicate<JavaClass> MESSAGING_CORE =
            DescribedPredicate.describe("메시징 코어의 클래스",
                    javaClass -> resideInAPackage("com.stove.common.messaging..").test(javaClass)
                            && !resideInAPackage("com.stove.common.messaging.kafka..").test(javaClass));

    @ArchTest
    public static final ArchRule 카프카는_JPA와_Outbox를_모른다 = noClasses()
            .that().resideInAPackage("com.stove.common.messaging.kafka..")
            .should().dependOnClassesThat(MESSAGING_CORE)
            .orShould().dependOnClassesThat().resideInAnyPackage("com.stove.common.jpa..", "jakarta.persistence..")
            .because("Kafka 소비만 필요한 앱(DB 없는 Store·게이트웨이·웹 BFF)에는 DB 트랜잭션 인프라를 요구하지 않는다")
            .allowEmptyShould(true);

    /**
     * 이벤트 계약(토픽·종류·본문·봉투·헤더)은 Kafka 레코드 타입만 안다. 리스너 기반(Spring Kafka)과 메시징 모듈은 모른다 —
     * 계약을 쓰는 곳(업무 모듈·Store)이 리스너 인프라나 코어를 끌고 오지 않게(결정 227).
     */
    @ArchTest
    public static final ArchRule 이벤트_계약은_리스너_인프라와_메시징_모듈을_모른다 = noClasses()
            .that().resideInAPackage("com.stove.common.contract..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.kafka..", "com.stove.common.messaging..")
            .because("계약은 봉투·헤더 규약만 가진다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 공용_패키지_순환_없음 = slices()
            .matching("com.stove.common.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 필드_주입_금지 = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 표준_출력_금지 = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 제네릭_예외_금지 = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.allowEmptyShould(true);
}
