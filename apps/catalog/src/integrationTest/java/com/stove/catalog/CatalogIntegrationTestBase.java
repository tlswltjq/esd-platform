package com.stove.catalog;

import com.stove.common.testcontainers.InfraContainers;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stove.outbox.relay-enabled=false",
            "spring.kafka.listener.auto-startup=false"
        })
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
public abstract class CatalogIntegrationTestBase {}
