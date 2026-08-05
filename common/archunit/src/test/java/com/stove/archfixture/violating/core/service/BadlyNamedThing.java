package com.stove.archfixture.violating.core.service;

import org.springframework.transaction.annotation.Transactional;

/**
 * 위반 픽스처 — {@code core.service} 에 있으면서 이름이 {@code Service} 로 끝나지 않는다.
 *
 * <p>{@code core_서비스_네이밍} 이 이것을 잡아야 한다. 이 클래스는 규칙이 실제로 위반을
 * 판정하는지 확인하기 위한 것이지 어디에도 쓰이지 않는다.
 */
@Transactional
public class BadlyNamedThing {

    @Transactional
    public void doWork() {
    }
}
