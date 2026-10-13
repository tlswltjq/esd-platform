package com.stove.common.security;

import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Servlet 앱의 인자 주입만 등록한다. JWT decoder와 접근 정책은 앱이 명시적으로 선택한다. */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSecurityAutoConfiguration {

    @Bean
    public WebMvcConfigurer currentIdentityWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentIdentityArgumentResolver());
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springdoc.core.utils.SpringDocUtils")
    static class DocumentationConfiguration {

        @Bean
        InitializingBean currentIdentityDocumentation() {
            return () -> SpringDocUtils.getConfig()
                    .addAnnotationsToIgnore(CurrentMemberId.class, CurrentSubject.class);
        }
    }
}
