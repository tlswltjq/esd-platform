package com.stove.catalog.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum CatalogDownloadError implements ApiErrorCode {
    PRODUCT_DOWNLOAD_RESTRICTED;
    @Override public String code() { return name(); }
    @Override public int statusCode() { return 403; }
    @Override public String defaultMessage() { return "상품 파일의 다운로드가 일시적으로 제한되었습니다."; }
}
