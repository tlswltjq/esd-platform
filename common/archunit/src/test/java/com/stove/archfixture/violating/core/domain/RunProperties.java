package com.stove.archfixture.violating.core.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fixture.run")
public record RunProperties(int batchSize) {}
