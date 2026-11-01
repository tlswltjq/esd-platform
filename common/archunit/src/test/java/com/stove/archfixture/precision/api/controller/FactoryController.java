package com.stove.archfixture.precision.api.controller;

import org.springframework.context.annotation.Bean;

public class FactoryController {
    @Bean public String misplacedFactory() { return "marker"; }
}
