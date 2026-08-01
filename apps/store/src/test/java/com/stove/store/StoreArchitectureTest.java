package com.stove.store;

import com.stove.common.archunit.ModuleHygieneRules;
import com.stove.common.archunit.ModulePackageRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** store 모듈 패키지 구조 검증. 규칙 본체는 common:archunit 에 한 벌만 둔다. */
@AnalyzeClasses(packages = "com.stove.store", importOptions = ImportOption.DoNotIncludeTests.class)
class StoreArchitectureTest {

    @ArchTest
    static final ArchTests 패키지_구조 = ArchTests.in(ModulePackageRules.class);

    @ArchTest
    static final ArchTests 모듈_위생 = ArchTests.in(ModuleHygieneRules.class);
}
