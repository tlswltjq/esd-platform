package com.stove.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CommonWebAutoConfigurationTest {

    @Test
    void contractsDoNotRequireServletRuntime() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("jakarta.servlet", "org.springframework.web.servlet"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("globalExceptionHandler");
                });
    }

    @Test
    void optionalIntegrationsCanBeAbsent() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "org.springdoc", "org.springframework.security", "org.springframework.data"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(TraceIdResponseFilter.class);
                    assertThat(context).doesNotHaveBean("apiDocumentationCustomizer");
                    assertThat(context).doesNotHaveBean("securityExceptionHandler");
                    assertThat(context).doesNotHaveBean("unknownPropertyExceptionHandler");
                });
    }

    @Test
    void availableIntegrationsAreRegistered() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiDocumentationCustomizer.class);
                    assertThat(context).hasSingleBean(SecurityExceptionHandler.class);
                    assertThat(context).hasSingleBean(UnknownPropertyExceptionHandler.class);
                });
    }
}
