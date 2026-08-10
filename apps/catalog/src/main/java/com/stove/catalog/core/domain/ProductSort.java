package com.stove.catalog.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 목록 조회가 허용하는 정렬 키와, 그 키를 엔티티 속성으로 옮기는 표. [D-024]
 *
 * <p><b>왜 목록이 필요한가</b> — 목록이 없으면 클라이언트가 보낸 문자열이 검사 없이
 * Spring Data 까지 내려가고, 거기서 <b>엔티티에 그 이름의 필드가 있느냐</b>가 곧 API 계약이 된다.
 * 계약을 정한 적이 없으니 엔티티가 대신 정하는 셈이고, 실제로 그래서 계약이 뒤집혀 있었다 —
 * 응답이 돌려주는 이름({@code productId})은 500 이고, 응답 어디에도 없는 내부 이름({@code id})만
 * 200 이었다. 여기 적히지 않은 이름은 이제 전부 {@code INVALID_REQUEST} 400 이다.
 *
 * <p><b>왜 좁게 시작하는가</b> — 목록에 적어야 열리는 것이 이 방식의 값이다.
 * {@code gameId}·{@code sellerId} 는 정렬이 아니라 필터의 대상이고, 목록이 {@code ON_SALE} 만
 * 돌려주므로 {@code status} 로 정렬하는 것은 의미가 없다. 필요해지면 한 줄 늘리면 된다 —
 * 엔티티에 필드가 생겼다고 저절로 열리지는 않는다.
 *
 * <p><b>왜 core.domain 인가</b> — 표의 양 끝이 여기 있다. 왼쪽은 {@link ProductView} 가
 * 내보내는 이름이고 오른쪽은 {@link Product} 의 속성이다. {@code ProductSortTest} 가
 * 그 둘을 전수로 대조하므로, 어느 쪽 필드를 지워도 이 표가 조용히 썩지 않는다.
 */
public final class ProductSort {

    /**
     * 요청 키(응답 계약의 이름) → 엔티티 속성.
     *
     * <p>{@code productId → id} 하나만 이름이 갈린다. 나머지는 항등이지만 그래도 적어 둔다 —
     * 표에 없는 것은 허용되지 않는다는 규칙이 예외를 갖는 순간 읽는 사람이 다시 추론해야 한다.
     *
     * <p>순서가 있는 맵을 쓴다. 오류 메시지가 허용 키를 나열하므로 실행마다 순서가 달라지면
     * 그 메시지를 고정하는 테스트를 쓸 수 없다.
     */
    private static final Map<String, String> ALLOWED = allowed();

    private static Map<String, String> allowed() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("productId", "id");
        keys.put("productCode", "productCode");
        keys.put("name", "name");
        keys.put("price", "price");
        return Collections.unmodifiableMap(keys);
    }

    private ProductSort() {
    }

    /** 허용 키 → 엔티티 속성. 전수 대조 테스트가 이 표를 그대로 읽는다. */
    public static Map<String, String> allowedKeys() {
        return ALLOWED;
    }

    /**
     * 정렬 키를 검사하고 엔티티 속성 이름으로 옮긴 {@link Pageable} 을 돌려준다.
     *
     * <p>컨트롤러가 아니라 서비스가 이것을 부르는 이유는 D-019·D-020 과 같다 —
     * 어댑터에만 두면 그 경로 하나만 지켜지고, 어댑터는 늘어난다.
     *
     * @throws BusinessException 허용 목록에 없는 정렬 키가 있으면 {@code INVALID_REQUEST}
     */
    public static Pageable apply(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> translated = new ArrayList<>();
        for (Sort.Order order : sort) {
            String property = ALLOWED.get(order.getProperty());
            if (property == null) {
                // 엔티티 타입명이나 필드명은 싣지 않는다. 인증 없는 공개 경로라
                // 오류 메시지가 내부 구조를 알려 주는 통로가 된다.
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "정렬할 수 없는 속성입니다: %s (허용: %s)"
                                .formatted(order.getProperty(), String.join(", ", ALLOWED.keySet())));
            }
            translated.add(order.withProperty(property));
        }

        Sort entitySort = Sort.by(translated);
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), entitySort)
                : Pageable.unpaged(entitySort);
    }
}
