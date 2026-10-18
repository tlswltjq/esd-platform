package com.stove.download.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

final class HttpResponseValues {
    private HttpResponseValues() {}

    static JsonNode data(JsonNode body) {
        if (body == null || !body.path("success").isBoolean()
                || !body.path("success").booleanValue() || !body.path("data").isObject()) throw invalid();
        return body.path("data");
    }

    static long number(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    static BusinessException invalid() {
        return new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "연동 서비스 응답이 올바르지 않습니다.");
    }
}
