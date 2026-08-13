package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.api.controller.dto.ProductResponse;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 정렬 허용 표가 양쪽 끝과 어긋나지 않는지 전수로 본다. [D-024]
 *
 * <p><b>키를 하나씩 적지 않는다.</b> {@code "price 가 있다"} 같은 단언은 표를 옮겨 적은 것에
 * 불과해서, 표가 틀려도 테스트도 같이 틀린 값을 들고 있게 된다({@code ErrorCodeTest} 와 같은 이유).
 *
 * <p>대신 <b>성질</b>을 건다 — 왼쪽은 응답이 실제로 내보내는 이름이어야 하고,
 * 오른쪽은 엔티티에 실제로 있는 속성이어야 한다. 이 둘이 걸려 있으면 {@link ProductResponse} 의
 * 필드 이름을 바꾸거나 {@link Product} 의 필드를 지웠을 때 표가 조용히 썩지 않고 여기서 깨진다.
 * 표가 썩으면 500 이 돌아온다 — 그것이 D-024 였다.
 *
 * <p><b>왜 {@code ProductView} 가 아니라 {@link ProductResponse} 인가</b> [D-025] —
 * 정렬 키는 <b>부르는 쪽이 응답에서 본 이름</b>이어야 하고, 응답을 만드는 것은 {@code ProductResponse} 다.
 * {@code ProductView} 는 스스로 "캐시 페이로드가 API 응답 계약과 분리된다"고 적어 둔 읽기 모델이라,
 * 거기에 걸어 두면 응답 필드가 {@code title} 로 바뀌어도 이 테스트는 초록으로 남는다.
 * core 의 테스트가 {@code api} 의 DTO 를 참조하는 것은 계층 규칙을 어기지 않는다 —
 * {@code CatalogArchitectureTest} 는 {@code ImportOption.DoNotIncludeTests} 로 돈다.
 *
 * <p>정렬 결과가 <b>항상 결정적인지</b>도 여기서 본다. 꼬리표가 빠지면 동값 구간의 순서가
 * statement 마다 달라져 페이지 경계에서 상품이 중복·유실된다(D-025).
 */
class ProductSortKeysTest {

    private static Set<String> responseNames() {
        return Arrays.stream(ProductResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 엔티티가 실제로 갖는 정렬 가능한 속성.
     *
     * <p>상위 클래스까지 훑는다. {@code Product} 는 {@code BaseTimeEntity} 를 상속하고
     * {@code createdAt}·{@code updatedAt} 은 거기 있다 — Spring Data 는 문제없이 해석하는데
     * {@code getDeclaredFields()} 만 보면 <b>맞는 키를 표에 넣는 순간 이 가드가 거짓으로 깨진다.</b>
     */
    private static Set<String> entityProperties() {
        Set<String> properties = new HashSet<>();
        for (Class<?> type = Product.class; type != null && type != Object.class; type = type.getSuperclass()) {
            Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName)
                    .forEach(properties::add);
        }
        return properties;
    }

    private static List<Sort.Order> ordersOf(Pageable pageable) {
        return pageable.getSort().toList();
    }

    @Test
    @DisplayName("검사할 표가 있다 — 이 테스트가 공허해지는 것부터 막는다")
    void tableIsNotEmpty() {
        assertThat(ProductSort.allowedKeys()).isNotEmpty();
    }

    @Test
    @DisplayName("허용 키는 전부 응답이 내보내는 이름이다")
    void everyKeyIsANameTheResponseShows() {
        // 응답에 없는 이름을 정렬 키로 받으면 부르는 쪽은 그 이름을 알아낼 방법이 없다.
        // 계약이 뒤집혀 있던 것이 정확히 그 상태였다.
        assertThat(ProductSort.allowedKeys().keySet()).isSubsetOf(responseNames());
    }

    @Test
    @DisplayName("매핑된 이름은 전부 엔티티에 실제로 있는 속성이다")
    void everyMappedNameExistsOnTheEntity() {
        // 여기가 어긋나면 400 이어야 할 것이 아니라 200 이어야 할 것이 500 이 된다.
        assertThat(ProductSort.allowedKeys().values()).isSubsetOf(entityProperties());
    }

    @Test
    @DisplayName("상속받은 속성도 엔티티의 속성으로 센다")
    void inheritedPropertiesCount() {
        // 이 단언이 없으면 위 가드가 getDeclaredFields() 로 되돌아가도 아무도 모른다.
        // BaseTimeEntity 의 시각 컬럼은 '최신순' 처럼 자연스러운 정렬 키의 후보다.
        assertThat(entityProperties()).contains("createdAt", "updatedAt");
    }

    @Test
    @DisplayName("엔티티 필드명은 계약이 아니다 — id 는 허용 키가 아니다")
    void entityFieldNameIsNotAKey() {
        // 위 두 성질만으로는 id 를 막지 못한다. Product 에 있고 응답에는 없으므로
        // '허용 키' 로 넣으면 첫 번째 성질이 걸어 주지만, 그 판정을 여기서 이름으로 못박아 둔다.
        assertThat(ProductSort.allowedKeys()).doesNotContainKey("id");
    }

    @Test
    @DisplayName("모르는 키는 INVALID_REQUEST 로 거절한다")
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> ProductSort.apply(PageRequest.of(0, 20, Sort.by("unknownField"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("여러 정렬 키 중 하나만 모르는 것이어도 거절한다")
    void oneUnknownKeyAmongManyIsEnough() {
        // 아는 것만 남기고 조용히 무시하면, 부르는 쪽은 자기가 요청한 정렬이
        // 적용되지 않은 것을 모른 채 결과를 신뢰한다.
        Sort mixed = Sort.by(Sort.Order.asc("price"), Sort.Order.desc("nope"));

        assertThatThrownBy(() -> ProductSort.apply(PageRequest.of(0, 20, mixed)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[D-025] 거절 메시지가 요청 문자열의 개행을 그대로 되싣지 않는다")
    void rejectionDoesNotEchoControlCharacters() {
        // 이 메시지는 응답으로도 나가고 GlobalExceptionHandler 의 로그로도 나간다.
        // 개행이 살아 있으면 인증 없는 공개 경로에서 로그 줄을 위조할 수 있다.
        Sort forged = Sort.by("price\n2026-08-13 ERROR 가짜 줄");

        assertThatThrownBy(() -> ProductSort.apply(PageRequest.of(0, 20, forged)))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("\n")
                .hasMessageNotContaining("\r");
    }

    @Test
    @DisplayName("[D-025] 표보다 많은 정렬 절은 거절한다")
    void tooManyOrdersAreRejected() {
        // sort 파라미터의 반복 횟수에는 상한이 없다. 전부 허용 키여도 수백 개 절짜리
        // ORDER BY 를 공개 경로에서 만들 수 있다. 표보다 많은 절은 반드시 중복이다.
        List<Sort.Order> tooMany = ProductSort.allowedKeys().keySet().stream()
                .flatMap(key -> Stream.of(Sort.Order.asc(key), Sort.Order.desc(key)))
                .toList();

        assertThatThrownBy(() -> ProductSort.apply(PageRequest.of(0, 20, Sort.by(tooMany))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("허용 키는 엔티티 이름으로 옮기고 방향과 페이지는 그대로 둔다")
    void translatesKeepingDirectionAndPage() {
        Pageable applied = ProductSort.apply(PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "productId")));

        Sort.Order order = applied.getSort().getOrderFor("id");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        // 번역이 페이지 정보를 잃으면 목록이 조용히 첫 페이지로 돌아간다.
        assertThat(applied.getPageNumber()).isEqualTo(2);
        assertThat(applied.getPageSize()).isEqualTo(5);
        // 옛 이름이 남아 있으면 저장소가 그것으로 다시 터진다.
        assertThat(applied.getSort().getOrderFor("productId")).isNull();
    }

    @Test
    @DisplayName("[D-025] 유일하지 않은 키로 정렬하면 마지막에 id 가 붙는다")
    void nonUniqueKeyGetsATieBreaker() {
        // price 는 동값이 얼마든지 있다. 꼬리표가 없으면 동순위의 순서를 DB 가 그때그때 정하고,
        // LIMIT/OFFSET 페이징에서 같은 상품이 두 번 나오거나 한 번도 안 나온다.
        Pageable applied = ProductSort.apply(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "price")));

        assertThat(ordersOf(applied))
                .containsExactly(Sort.Order.asc("price"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("[D-025] 이미 id 로 정렬하면 꼬리표를 두 번 붙이지 않는다")
    void tieBreakerIsNotDuplicated() {
        // productId 는 id 로 번역된다. 여기에 또 붙이면 같은 속성의 두 번째 절이 되어
        // 아무 일도 하지 않으면서 방향만 헷갈리게 만든다.
        Pageable applied = ProductSort.apply(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "productId")));

        assertThat(ordersOf(applied)).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    @DisplayName("[D-025] 정렬을 주지 않으면 기본 순서는 최신순이다")
    void unsortedGetsTheDefaultOrder() {
        // ORDER BY 가 아예 없는 LIMIT/OFFSET 도 페이지 사이의 순서를 보장하지 않는다.
        // 가장 흔한 요청(정렬 없는 첫 페이지)이 바로 그 상태였다.
        Pageable applied = ProductSort.apply(PageRequest.of(0, 20));

        assertThat(ordersOf(applied)).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("unpaged 로 불러도 터지지 않는다")
    void unpagedIsHandled() {
        // 컨트롤러는 @PageableDefault 라 항상 paged 지만, 서비스는 직접 호출될 수 있다.
        // PageRequest.of 는 unpaged 에 예외를 던지므로 분기가 없으면 여기서 500 이 난다.
        assertThatCode(() -> ProductSort.apply(Pageable.unpaged(Sort.by("price"))))
                .doesNotThrowAnyException();

        assertThat(ordersOf(ProductSort.apply(Pageable.unpaged(Sort.by("price")))))
                .containsExactly(Sort.Order.asc("price"), Sort.Order.desc("id"));
    }
}
