package com.stove.common.security;

import java.math.BigDecimal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** 검증된 principal에서 식별 정보만 추출한다. DB 조회나 업무 실행을 수행하지 않는다. */
public final class CurrentIdentity {

    private CurrentIdentity() {
    }

    public static Long memberId(Jwt jwt) {
        Object claim = jwt == null ? null : jwt.getClaim("member_id");
        if (claim instanceof Number number) {
            try {
                long memberId = new BigDecimal(number.toString()).longValueExact();
                if (memberId > 0) {
                    return memberId;
                }
            } catch (ArithmeticException | NumberFormatException invalidNumber) {
                // 유효한 양의 Long으로 표현할 수 없는 값은 다른 회원 ID로 변환하지 않는다.
            }
        }
        throw new AccessDeniedException("member_id claim is required");
    }

    public static String subject(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AccessDeniedException("subject claim is required");
        }
        return subject;
    }
}
