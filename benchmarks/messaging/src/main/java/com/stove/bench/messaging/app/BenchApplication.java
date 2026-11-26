package com.stove.bench.messaging.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Kafka 기준선 층 2 시험 앱(E13·결정 234). 운영 앱과 같은 {@code common:messaging}·{@code common:messaging-kafka} 자동 구성 위에서
 * 시험 리스너가 Inbox와 시험 표 한 행을 한 트랜잭션으로 쓴다. 업무 처리 성능이 아니라 전달 계층을 잰다.
 *
 * <p>격리처는 기본이 소비 모듈 DB(격리 표·키 정지)이고, 프로필 {@code dlt}면 DLT다({@code config.DltModeConfiguration}).
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.stove.bench.messaging.app", "com.stove.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.stove.bench.messaging.app", "com.stove.common.messaging"})
public class BenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenchApplication.class, args);
    }
}
