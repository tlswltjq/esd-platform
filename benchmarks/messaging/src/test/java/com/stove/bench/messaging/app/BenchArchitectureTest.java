package com.stove.bench.messaging.app;

import com.stove.common.archunit.AppArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** 시험 앱도 운영 앱과 같은 구조 규칙을 지킨다 — 리스너·서비스·Inbox 모양이 같아야 같은 경로를 잰다(결정 234). */
@AnalyzeClasses(packages = "com.stove.bench.messaging.app", importOptions = {
        ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeGradleTestFixtures.class})
class BenchArchitectureTest {
    @ArchTest static final ArchTests 공통_아키텍처 = ArchTests.in(AppArchitectureRules.class);
}
