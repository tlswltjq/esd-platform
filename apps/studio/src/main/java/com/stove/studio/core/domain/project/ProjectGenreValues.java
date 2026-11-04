package com.stove.studio.core.domain.project;

import com.stove.common.kafka.contract.genre.GenreCatalog;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.List;

public final class ProjectGenreValues {
    private ProjectGenreValues() {}
    public static List<String> draft(List<String> codes) {
        try { return GenreCatalog.standard().draft(codes); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "장르는 알려진 코드로 최대 3개를 중복 없이 선택해 주세요."); }
    }
    public static void requireSubmission(List<String> codes) {
        try { GenreCatalog.standard().submission(codes); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.CONFLICT, "선택 가능한 장르 1~3개를 준비해 주세요."); }
    }
}
