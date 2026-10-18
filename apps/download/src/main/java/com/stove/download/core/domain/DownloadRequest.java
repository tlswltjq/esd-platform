package com.stove.download.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record DownloadRequest(String buyerSubject, String productCode) {
    public DownloadRequest {
        if (!DownloadValues.subject(buyerSubject))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!DownloadValues.productCode(productCode))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 코드가 올바르지 않습니다.");
    }
}
