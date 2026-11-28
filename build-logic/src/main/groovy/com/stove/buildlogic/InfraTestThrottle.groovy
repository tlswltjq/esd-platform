package com.stove.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * 실 인프라를 띄우는 테스트 태스크가 동시에 몇 개까지 돌 수 있는지를 정하는 자리.
 *
 * <p>모듈마다 자기 컨테이너 스택을 띄우므로(common:testcontainers 참고) 동시 실행 수가
 * 곧 동시 컨테이너 수다. 예전에는 이것을 {@code org.gradle.workers.max} 로 눌렀는데,
 * 그 값은 <b>컴파일과 단위 테스트까지 같이</b> 묶는다 — 컨테이너를 지키려고 2 로 낮추면
 * 인프라가 필요 없는 51개 클래스도 함께 느려졌다.
 *
 * <p>공유 빌드 서비스는 그것을 쓰겠다고 선언한 태스크만 센다. 그래서 이제
 * {@code org.gradle.workers.max} 는 머신의 코어 수대로 올리고, 컨테이너 스택만 여기서 조인다.
 */
abstract class InfraTestThrottle implements BuildService<BuildServiceParameters.None> {
}
