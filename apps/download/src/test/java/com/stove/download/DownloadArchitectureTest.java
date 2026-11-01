package com.stove.download;

import com.stove.common.archunit.AppArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** 공통 규칙을 한 묶음으로 등록해 개별 규칙의 적용 누락을 막는다. */
@AnalyzeClasses(packages = "com.stove.download", importOptions = {
        ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeGradleTestFixtures.class})
class DownloadArchitectureTest {
    @ArchTest static final ArchTests 공통_아키텍처 = ArchTests.in(AppArchitectureRules.class);
}
