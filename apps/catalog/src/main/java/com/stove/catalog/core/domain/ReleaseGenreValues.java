package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.genre.GenreCatalog;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.List;

public final class ReleaseGenreValues {
    private ReleaseGenreValues() {}
    public static void requirePublished(List<String> codes) {
        try { GenreCatalog.standard().published(codes); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "장르 자료가 올바르지 않습니다."); }
    }
}
