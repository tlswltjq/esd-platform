package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
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
 * 오른쪽은 엔티티에 실제로 있는 속성이어야 한다. 이 둘이 걸려 있으면 {@link ProductView} 의
 * 필드 이름을 바꾸거나 {@link Product} 의 필드를 지웠을 때 표가 조용히 썩지 않고 여기서 깨진다.
 * 표가 썩으면 500 이 돌아온다 — 그것이 D-024 였다.
 */
class ProductSortKeysTest {

    private static Set<String> responseNames() {
        return Arrays.stream(ProductView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> entityProperties() {
        return Arrays.stream(Product.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());
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
    @DisplayName("엔티티 필드명은 계약이 아니다 — id 는 허용 키가 아니다")
    void entityFieldNameIsNotAKey() {
        // 위 두 성질만으로는 id 를 막지 못한다. Product 에 있고 ProductView 에는 없으므로
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
    @DisplayName("정렬이 없으면 손대지 않는다")
    void unsortedPassesThrough() {
        // 가드가 한 칸 넘치게 조여져 무정렬까지 막는 것을 막는다.
        Pageable unsorted = PageRequest.of(0, 20);

        assertThat(ProductSort.apply(unsorted)).isEqualTo(unsorted);
    }

    @Test
    @DisplayName("unpaged 로 불러도 터지지 않는다")
    void unpagedIsHandled() {
        // 컨트롤러는 @PageableDefault 라 항상 paged 지만, 서비스는 직접 호출될 수 있다.
        // PageRequest.of 는 unpaged 에 예외를 던지므로 분기가 없으면 여기서 500 이 난다.
        assertThatCode(() -> ProductSort.apply(Pageable.unpaged(Sort.by("price"))))
                .doesNotThrowAnyException();
    }
}
