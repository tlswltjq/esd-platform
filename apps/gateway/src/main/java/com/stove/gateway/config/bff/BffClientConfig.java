package com.stove.gateway.config.bff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties.class)
public class BffClientConfig {
    @Bean
    ReactiveClientRegistrationRepository bffRegistrations(
            org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties properties,
            @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${BFF_PUBLIC_AUTH_BASE:http://localhost:8080}") String publicBase) {
        var registrations = new org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientPropertiesMapper(properties)
                .asClientRegistrations();
        var bff = org.springframework.security.oauth2.client.registration.ClientRegistration
                .withClientRegistration(registrations.get("gateway-bff"))
                .issuerUri(issuer)
                .providerConfigurationMetadata(java.util.Map.of("issuer", issuer,
                        "end_session_endpoint", publicBase + "/connect/logout"))
                .build();
        return new org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository(bff);
    }
    @Bean
    ServerOAuth2AuthorizationRequestResolver bffAuthorizationRequestResolver(ReactiveClientRegistrationRepository clients) {
        var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(clients);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
