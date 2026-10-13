package com.stove.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class CommonSecurityAutoConfigurationTest {

    @Test
    void jwtSupportDoesNotRequireMvcOrCommonWeb() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springframework.web.servlet", "com.stove.common.web"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("currentIdentityWebMvcConfigurer");
                });
    }

    @Test
    void servletIdentitySupportDoesNotDefineApplicationSecurityPolicy() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WebMvcConfigurer.class);
                    assertThat(context).doesNotHaveBean("securityFilterChain");
                    assertThat(context).doesNotHaveBean("jwtDecoder");
                });
    }

    @Test
    void mvcIdentityInjectionDoesNotRequireOpenApi() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springdoc"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WebMvcConfigurer.class);
                    assertThat(context).doesNotHaveBean("currentIdentityDocumentation");
                });
    }
}
