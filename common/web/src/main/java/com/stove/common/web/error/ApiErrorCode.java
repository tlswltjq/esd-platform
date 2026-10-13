package com.stove.common.web.error;

/** HTTP 응답에 사용할 오류 계약. 업무 오류의 정의는 각 앱이 소유한다. */
public interface ApiErrorCode {

    String code();

    int statusCode();

    String defaultMessage();
}
