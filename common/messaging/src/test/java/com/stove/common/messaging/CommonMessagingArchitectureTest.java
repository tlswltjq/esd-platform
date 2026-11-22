package com.stove.common.messaging;

import com.stove.common.archunit.CommonLibraryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/**
 * 공용 라이브러리 검증. 이 모듈의 클래스패스가 common:web / common:contract / common:jpa 까지
 * 끌고 오므로 한 번의 실행이 그 넷을 함께 본다. adapter 모듈은 각자 같은 규칙을 건다(결정 209).
 */
@AnalyzeClasses(packages = "com.stove.common", importOptions = {
        ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeGradleTestFixtures.class})
class CommonMessagingArchitectureTest {

    @ArchTest
    static final ArchTests 공용_라이브러리_규칙 = ArchTests.in(CommonLibraryRules.class);
}
