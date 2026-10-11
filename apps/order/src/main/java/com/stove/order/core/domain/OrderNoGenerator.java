package com.stove.order.core.domain;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 주문번호 = ORD + yyyyMMdd + 난수 10자리.
 * 날짜 프리픽스는 운영 조회/파티셔닝에, 난수는 추측 불가능성에 쓰인다.
 */
@Component
public class OrderNoGenerator {

    /** 주문번호 날짜 부분의 출력 형식. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 주문번호 임의 문자열에 사용할 문자 집합. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    /** 주문번호 충돌 가능성을 줄일 난수 생성기. */
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder("ORD").append(LocalDate.now().format(DATE));
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
