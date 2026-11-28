package com.stove.common.web;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * HTTP 요청 관측({@code http.server.requests})에 응답의 오류 코드를 {@code error.code} 태그로 더한다(결정 error-observability).
 *
 * <p>상태 코드만으로는 어떤 업무 오류였는지 알 수 없다(409 하나에 여러 코드가 있다). 코드별 추이를 로그를 세지 않고 지표로 본다.
 * 값은 {@link ApiErrorCodeAdvice} 가 요청에 남긴 것을 읽는다. 오류 코드는 서버가 정의한 유한한 집합이라 태그 값이 늘지 않는다.
 *
 * <p><b>성공한 요청에도 {@value #NO_ERROR} 로 태그를 단다.</b> Prometheus 는 이름이 같은 지표가 같은 태그 집합을 갖기를 요구해,
 * 실패한 요청에만 달면 그 시계열이 등록되지 않는다.
 */
public class ApiErrorObservationConvention extends DefaultServerRequestObservationConvention {

    public static final String ERROR_CODE = "error.code";
    public static final String NO_ERROR = "none";

    /** 응답의 오류 코드를 관측이 끝날 때까지 들고 있는 요청 속성. */
    static final String REQUEST_ATTRIBUTE = ApiErrorObservationConvention.class.getName() + ".errorCode";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(errorCode(context));
    }

    private static KeyValue errorCode(ServerRequestObservationContext context) {
        Object code = context.getCarrier() == null ? null : context.getCarrier().getAttribute(REQUEST_ATTRIBUTE);
        return KeyValue.of(ERROR_CODE, code instanceof String text && !text.isBlank() ? text : NO_ERROR);
    }
}
