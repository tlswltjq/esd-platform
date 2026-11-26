package com.stove.common.contract.archfixture;

import com.stove.common.messaging.archfixture.CoreFixture;

/** 위반: 이벤트 계약이 메시징 모듈을 안다. */
public record ContractUsingCore(CoreFixture core) {
}
