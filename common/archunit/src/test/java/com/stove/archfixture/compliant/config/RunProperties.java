package com.stove.archfixture.compliant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fixture.run")
public record RunProperties(int batchSize) {}
