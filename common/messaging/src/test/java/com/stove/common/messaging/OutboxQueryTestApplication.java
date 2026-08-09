package com.stove.common.messaging;

import com.stove.common.messaging.ops.OutboxOpsController;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * {@code common:messaging} 자체를 JPA 와 함께 띄우기 위한 테스트 전용 부트 앱.
 *
 * <p>이 모듈은 라이브러리라 실행 가능한 애플리케이션이 없다. 그런데 여기 있는
 * 네이티브 쿼리는 실제 DB 없이는 의미를 확인할 수 없어서, 검증을 앱 모듈에 얹어 두고 있었다.
 * 그러면 9개 서비스가 의존하는 쿼리의 회귀 방어선이 앱 하나에 인질로 잡힌다 —
 * 그 앱이 테스트를 옮기거나 지우면 조용히 사라진다.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = OutboxOpsController.class))
public class OutboxQueryTestApplication {

    // 이 테스트 앱만 common:messaging 안에 있어서 ops 컨트롤러까지 스캔한다.
    // 실제 서비스는 자기 패키지만 스캔하고 ops 는 자동 구성으로만 들어오므로 겹칠 일이 없다.
    // 여기서 걸러내지 않으면 자동 구성이 만든 빈과 별개로 스캔이 하나 더 등록하고,
    // 그쪽은 서비스 빈을 못 찾아 컨텍스트가 깨진다.
}
