package com.stove.gateway.config.bff;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;

@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class BffClientConfig {
    @Bean
    ReactiveClientRegistrationRepository bffRegistrations(
            OAuth2ClientProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${BFF_PUBLIC_AUTH_BASE:http://localhost:8080}") String publicBase) {
        Map<String, ClientRegistration> registrations = new OAuth2ClientPropertiesMapper(properties)
                .asClientRegistrations();
        ClientRegistration bff = ClientRegistration
                .withClientRegistration(registrations.get("gateway-bff"))
                .issuerUri(issuer)
                .providerConfigurationMetadata(Map.of("issuer", issuer,
                        "end_session_endpoint", publicBase + "/connect/logout"))
                .build();
        return new InMemoryReactiveClientRegistrationRepository(bff);
    }
    @Bean
    ServerOAuth2AuthorizationRequestResolver bffAuthorizationRequestResolver(ReactiveClientRegistrationRepository clients) {
        DefaultServerOAuth2AuthorizationRequestResolver resolver = new DefaultServerOAuth2AuthorizationRequestResolver(clients);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
