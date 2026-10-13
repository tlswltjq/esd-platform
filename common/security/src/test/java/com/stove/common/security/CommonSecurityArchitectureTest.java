package com.stove.common.security;

import com.stove.common.archunit.CommonLibraryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(packages = "com.stove.common", importOptions = ImportOption.DoNotIncludeTests.class)
class CommonSecurityArchitectureTest {

    @ArchTest
    static final ArchTests 공용_라이브러리_규칙 = ArchTests.in(CommonLibraryRules.class);
}
