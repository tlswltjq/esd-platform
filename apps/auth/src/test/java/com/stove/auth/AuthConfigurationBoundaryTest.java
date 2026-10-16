package com.stove.auth;

import com.stove.common.archunit.ModulePackageRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.stove.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class AuthConfigurationBoundaryTest {
    @ArchTest static final ArchRule bindingLocation = ModulePackageRules.설정_바인딩은_config_에_둔다;
    @ArchTest static final ArchRule bindingDependencies = ModulePackageRules.설정_객체는_config_밖으로_전달하지_않는다;
    @ArchTest static final ArchRule environmentAccess = ModulePackageRules.설정_환경은_config_에서만_읽는다;
    @ArchTest static final ArchRule valueInjection = ModulePackageRules.Value_바인딩은_config_에서만_한다;
}
