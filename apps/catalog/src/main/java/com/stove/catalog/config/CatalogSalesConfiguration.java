package com.stove.catalog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class CatalogSalesConfiguration {
    @Bean Clock catalogClock() { return Clock.systemUTC(); }
}
