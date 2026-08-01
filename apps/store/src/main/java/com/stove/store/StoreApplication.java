package com.stove.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 유저 대면 진열 서비스.
 *
 * <p>쓰기 모델(catalog)과 읽기 모델(여기)을 분리한 CQRS 형태다.
 * 상품 마스터를 직접 소유하지 않고 {@code ProductChanged} 이벤트로 검색 색인만 동기화하므로,
 * catalog 가 죽어도 진열/검색은 계속 서비스된다.
 */
@SpringBootApplication
public class StoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
