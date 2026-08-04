package com.stove.order.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주문번호 생성기.
 *
 * <p>이 값은 order 안에서만 쓰이지 않는다 — payment·license·settlement 가 전부
 * 주문번호를 <b>키로</b> 잡는다. 멱등 판정, 파티션 키, 원장 유니크 제약이 여기에 매달려 있어
 * 형식이나 충돌 특성이 바뀌면 그 영향이 서비스 밖으로 나간다.
 */
class OrderNoGeneratorTest {

    private final OrderNoGenerator generator = new OrderNoGenerator();

    @Test
    @DisplayName("ORD + 날짜 8자리 + 난수 10자리 형식이다")
    void hasExpectedShape() {
        String orderNo = generator.generate();

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(orderNo).hasSize(21);
        assertThat(orderNo).startsWith("ORD" + today);
    }

    @Test
    @DisplayName("주문번호 컬럼(길이 40)에 들어간다")
    void fitsInTheColumn() {
        // Order.orderNo 는 length = 40 unique 다. 형식을 늘릴 때 여기서 먼저 걸린다.
        assertThat(generator.generate().length()).isLessThanOrEqualTo(40);
    }

    @Test
    @DisplayName("혼동하기 쉬운 I·L·O·U 를 쓰지 않는다")
    void avoidsAmbiguousCharacters() {
        // 운영자가 주문번호를 눈으로 옮겨 적는 일이 있다. 1/I, 0/O 를 섞으면 조회가 어긋난다.
        String randomPart = generator.generate().substring(11);

        assertThat(randomPart).doesNotContain("I").doesNotContain("L")
                .doesNotContain("O").doesNotContain("U");
    }

    @Test
    @DisplayName("연달아 만들어도 겹치지 않는다")
    void doesNotCollide() {
        // 난수 10자리 × 32글자 = 32^10. 충돌 확률 자체보다, 난수 자리가 줄거나
        // 시퀀스로 바뀌는 변경을 잡는 것이 목적이다.
        Set<String> generated = new HashSet<>();
        IntStream.range(0, 5_000).forEach(i -> generated.add(generator.generate()));

        assertThat(generated).hasSize(5_000);
    }
}
