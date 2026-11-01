package com.stove.archfixture.precision.adapter;

import org.springframework.context.annotation.Bean;

public class FactoryClient {
    @Bean public String misplacedFactory() { return "marker"; }
}
