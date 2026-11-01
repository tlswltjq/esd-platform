package com.stove.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.stove.common.archunit.AppArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

/** 공통 규칙을 한 묶음으로 등록해 개별 규칙의 적용 누락을 막는다. */
@AnalyzeClasses(packages = "com.stove.payment", importOptions = {
        ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeGradleTestFixtures.class})
class PaymentArchitectureTest {
    @ArchTest static final ArchTests 공통_아키텍처 = ArchTests.in(AppArchitectureRules.class);

    @ArchTest static final ArchRule core_는_추적_라이브러리를_모른다 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage("io.micrometer.tracing..", "io.opentelemetry..");
}
