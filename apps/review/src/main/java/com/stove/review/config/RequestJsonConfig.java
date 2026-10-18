package com.stove.review.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestJsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictRequests() {
        return builder ->
                builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
