package com.stove.archfixture.precision.core.service;

import org.springframework.context.annotation.Bean;

public class BuildValueService {
    @Bean public String misplacedFactory() { return "marker"; }
}
