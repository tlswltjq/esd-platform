package com.stove.archfixture.violating.core.service;

import org.springframework.transaction.annotation.Transactional;

/**
 * 이름의 업무 의미는 작성 지침에서 검토한다. 이 이름은 PascalCase 자동 검사의 허용 대상이다.
 */
@Transactional
public class BadlyNamedThing {

    @Transactional
    public void doWork() {
    }
}
