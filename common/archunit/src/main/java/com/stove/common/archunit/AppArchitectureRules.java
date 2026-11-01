package com.stove.common.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** 앱이 한 묶음으로 등록하는 규칙. 역할에 없는 계층은 개별 규칙이 빈 대상을 허용한다. */
public final class AppArchitectureRules {
    private AppArchitectureRules() { }

    @ArchTest static final ArchTests 패키지_구조 = ArchTests.in(ModulePackageRules.class);
    @ArchTest static final ArchTests 모듈_위생 = ArchTests.in(ModuleHygieneRules.class);
    @ArchTest static final ArchTests 이벤트_입력 = ArchTests.in(EventInputRules.class);
    @ArchTest static final ArchTests 이벤트_순서 = ArchTests.in(EventOrderingRules.class);
    @ArchTest static final ArchTests 컨슈머_그룹 = ArchTests.in(ConsumerGroupRules.class);

    @ArchTest
    static void 앱_검사_대상은_비어있지_않다(JavaClasses classes) {
        if (classes.size() == 0) {
            throw new AssertionError("앱 검사 대상이 비어 있다. @AnalyzeClasses의 패키지와 importOptions를 확인할 것.");
        }
    }
}
