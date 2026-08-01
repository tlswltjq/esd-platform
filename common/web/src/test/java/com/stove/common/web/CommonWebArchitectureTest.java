package com.stove.common.web;

import com.stove.common.archunit.CommonLibraryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** 공용 라이브러리 검증. common:web 과 그 아래 common:core 를 본다. */
@AnalyzeClasses(packages = "com.stove.common", importOptions = ImportOption.DoNotIncludeTests.class)
class CommonWebArchitectureTest {

    @ArchTest
    static final ArchTests 공용_라이브러리_규칙 = ArchTests.in(CommonLibraryRules.class);
}
