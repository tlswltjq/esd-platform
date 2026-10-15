package com.stove.store.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum StoreErrorCode implements ApiErrorCode {
    NOT_READY;
    @Override public String code() { return "STORE_NOT_READY"; }
    @Override public int statusCode() { return 503; }
    @Override public String defaultMessage() { return "공개 상품 초기 동기화가 완료되지 않았습니다."; }
}
