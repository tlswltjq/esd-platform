package com.stove.studio.core.domain.project;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record StudioPage(int page, int size) {
    public StudioPage {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1~100이며 조회 구간은 정수 범위여야 합니다.");
    }
}
