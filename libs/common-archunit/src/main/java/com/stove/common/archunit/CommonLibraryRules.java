package com.stove.common.archunit;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * {@code libs/common-*} 에 적용하는 규칙.
 *
 * <p>{@link ModulePackageRules} 는 서비스 모듈의 core/api/infrastructure 배치를 다루므로
 * 라이브러리에는 맞지 않는다. 라이브러리에서 지켜야 하는 것은 배치가 아니라 <b>방향</b>이다 —
 * 공용 코드가 앱을 알기 시작하면 공용이 아니게 된다.
 *
 * <p>지금은 Gradle 의존 그래프가 이 방향을 우연히 지키고 있을 뿐이고,
 * 규칙으로 적어두지 않으면 프로젝트 의존 한 줄로 조용히 뒤집힌다.
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
     * <p>{@code libs/common-event} 의 의존은 Jackson 과 Kafka 까지다 —
     * 봉투 규약은 포함하되 JPA 는 포함하지 않는다(build.gradle 에 적어둔 그 결정이다).
     * 페이로드에 엔티티가 섞이면 이벤트가 발행 측 스키마에 묶인다.
     */
    @ArchTest
    public static final ArchRule 이벤트_계약은_영속성을_모른다 = noClasses()
            .that().resideInAPackage("com.stove.common.event..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..",
                    "org.springframework.data..")
            .because("이벤트 페이로드는 저장 방식과 독립이다")
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
