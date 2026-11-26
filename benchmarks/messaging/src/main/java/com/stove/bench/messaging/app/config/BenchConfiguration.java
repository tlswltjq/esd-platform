package com.stove.bench.messaging.app.config;

import com.stove.bench.messaging.app.adapter.file.FileAttemptLog;
import com.stove.bench.messaging.app.api.application.ReceiveBenchMessage;
import com.stove.bench.messaging.app.core.service.RecordBenchMessage;
import com.stove.common.messaging.kafka.KeyQuarantine;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시험 앱 조립. 회차 폴더와 기록 주기는 측정 스크립트가 환경으로 준다. */
@Configuration(proxyBeanMethods = false)
public class BenchConfiguration {

    @Bean
    Clock benchClock() {
        return Clock.systemUTC();
    }

    /**
     * 리스너 컨테이너가 멈춘 뒤 싱글턴이 닫히므로, 정상 종료면 마지막 시도까지 파일에 남는다.
     *
     * @param processTag 같은 회차에서 다시 띄운 프로세스가 앞 기록을 덮지 않게 파일 이름에 붙인다.
     */
    @Bean(destroyMethod = "close")
    FileAttemptLog attemptLog(@Value("${stove.bench.run-dir}") Path runDir,
                              @Value("${stove.bench.attempt-flush-ms:1000}") long flushMillis) {
        String processTag = System.currentTimeMillis() + "-" + ProcessHandle.current().pid();
        return new FileAttemptLog(runDir, processTag, flushMillis);
    }

    /**
     * 기록할 격리처 계열({@code stove.bench.recovery})과 실제로 뜬 격리처가 같은지 모든 빈을 만든 뒤 확인한다(PR #253 리뷰 R3).
     * 다르면 회차가 다른 계열로 기록되므로 기동을 멈춘다.
     */
    @Bean
    SmartInitializingSingleton benchRecoveryModeCheck(@Value("${stove.bench.recovery}") String recovery,
                                                      ObjectProvider<KeyQuarantine> quarantine) {
        return () -> requireRecoveryMode(recovery, quarantine.getIfAvailable() != null);
    }

    static void requireRecoveryMode(String recovery, boolean quarantinePresent) {
        boolean consistent = switch (recovery) {
            case "quarantine" -> quarantinePresent;
            case "dlt" -> !quarantinePresent;
            default -> throw new IllegalStateException("stove.bench.recovery 는 quarantine·dlt 중 하나다: " + recovery);
        };
        if (!consistent) {
            throw new IllegalStateException("stove.bench.recovery=%s 인데 격리 표(KeyQuarantine)가 %s — 프로필 dlt 로만 계열을 바꿀 것"
                    .formatted(recovery, quarantinePresent ? "켜져 있다" : "없다"));
        }
    }

    @Bean
    ReceiveBenchMessage receiveBenchMessage(RecordBenchMessage service, FileAttemptLog attemptLog, Clock benchClock) {
        return new ReceiveBenchMessage(service, attemptLog, benchClock);
    }
}
