package com.stove.archfixture.violating.core.service;

import com.stove.archfixture.compliant.config.RunProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

public class ResolveConfiguredWork {
    private final RunProperties properties;
    private final Environment environment;
    public ResolveConfiguredWork(RunProperties properties, Environment environment,
            @Value("${fixture.delay:1000}") long delay) {
        this.properties = properties;
        this.environment = environment;
    }
}
