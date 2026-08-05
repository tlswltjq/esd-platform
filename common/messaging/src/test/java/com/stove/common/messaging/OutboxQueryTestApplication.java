package com.stove.common.messaging;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code common:messaging} 자체를 JPA 와 함께 띄우기 위한 테스트 전용 부트 앱.
 *
 * <p>이 모듈은 라이브러리라 실행 가능한 애플리케이션이 없다. 그런데 여기 있는
 * 네이티브 쿼리는 실제 DB 없이는 의미를 확인할 수 없어서, 검증을 앱 모듈에 얹어 두고 있었다.
 * 그러면 9개 서비스가 의존하는 쿼리의 회귀 방어선이 앱 하나에 인질로 잡힌다 —
 * 그 앱이 테스트를 옮기거나 지우면 조용히 사라진다.
 */
@SpringBootApplication
public class OutboxQueryTestApplication {
}
