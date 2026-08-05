package com.stove.archfixture.violating.infrastructure;

import org.springframework.stereotype.Component;

/**
 * 위반 픽스처 — {@code infrastructure} 의 {@code Mock*} 스텁인데 프로파일/조건 격리가 없다.
 *
 * <p>실제로 이런 빈이 있으면 운영에서 조용히 돌거나 실제 어댑터와 충돌한다(결정 9).
 */
@Component
public class MockUnguardedClient {
}
