package com.stove.download;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 배포/다운로드 서비스.
 *
 * <p>버전마다 파일 목록이 달라지는 패치 매니페스트는 스키마가 유동적이라 MongoDB 를 쓴다.
 * 소유권 판정은 license-service 를 동기 호출하지 않고 이벤트로 받은 <b>권한 사본</b>으로 처리한다
 * (다운로드는 트래픽이 가장 크고, license 장애가 다운로드 장애로 번지면 안 되기 때문).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DownloadServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownloadServiceApplication.class, args);
    }
}
