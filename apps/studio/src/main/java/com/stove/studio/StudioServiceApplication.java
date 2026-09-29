package com.stove.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EntityScan(basePackages = {"com.stove.studio", "com.stove.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.stove.studio", "com.stove.common.messaging"})
@EnableAsync
public class StudioServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioServiceApplication.class, args);
    }
}
