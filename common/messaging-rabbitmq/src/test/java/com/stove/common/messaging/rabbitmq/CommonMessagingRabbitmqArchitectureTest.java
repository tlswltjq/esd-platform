package com.stove.common.messaging.rabbitmq;

import com.stove.common.archunit.CommonLibraryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** 공용 라이브러리 규칙을 이 모듈의 클래스패스(common:messaging-rabbitmq 과 그 의존)에 건다. 결정 209 메시징 경계 규칙을 포함한다. */
@AnalyzeClasses(packages = "com.stove.common", importOptions = ImportOption.DoNotIncludeTests.class)
class CommonMessagingRabbitmqArchitectureTest {

    @ArchTest
    static final ArchTests 공용_라이브러리_규칙 = ArchTests.in(CommonLibraryRules.class);
}
