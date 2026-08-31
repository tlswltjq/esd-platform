package com.stove.catalog.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.EchoedInput;
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
 * <b>여기 적히지 않은 이름은 전부 400 이다</b> — 목록에 적어야 열리는 것이 이 방식의 값이다.
 *
 * <p>표를 통과한 뒤 마지막 절로 항상 {@code id desc} 가 붙는다 [D-025] —
 * 없으면 같은 상품이 두 페이지에 나온다. docs/code-notes.md
 */
public final class ProductSort {

    /**
     * 요청 키(응답 계약의 이름) → 엔티티 속성.
     * <b>순서가 있는 맵이어야 한다</b> — 오류 메시지가 허용 키를 나열한다.
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

    /** 오류 메시지가 나열하는 허용 키. 표가 불변이므로 요청마다 다시 잇지 않는다. */
    private static final String ALLOWED_KEYS = String.join(", ", ALLOWED.keySet());

    /** 마지막에 붙는 꼬리표 속성. 유일하고 값이 변하지 않아야 하며 방향은 {@code desc} 고정. */
    private static final String TIE_BREAKER = "id";

    /**
     * 정렬 절 개수 상한. <b>스프링은 {@code sort} 의 반복 횟수를 막지 않는다</b> —
     * 상한이 없으면 공개 경로에서 수백 개 절짜리 {@code ORDER BY} 를 만들 수 있다.
     */
    private static final int MAX_ORDERS = ALLOWED.size();

    private ProductSort() {
    }

    /** 허용 키 → 엔티티 속성. 전수 대조 테스트가 이 표를 그대로 읽는다. */
    public static Map<String, String> allowedKeys() {
        return ALLOWED;
    }

    /**
     * 정렬 키를 검사하고 엔티티 속성으로 옮긴 {@link Pageable} 을 돌려준다.
     * 마지막에는 항상 {@code id desc} 가 붙는다. 부르는 쪽이 컨트롤러가 아닌 이유는 D-019·D-020.
     *
     * @throws BusinessException 허용 목록에 없는 키가 있거나 절이 너무 많으면 {@code INVALID_REQUEST}
     */
    public static Pageable apply(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.stream().count() > MAX_ORDERS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "정렬 키는 최대 %d 개입니다. (허용: %s)".formatted(MAX_ORDERS, ALLOWED_KEYS));
        }

        List<Sort.Order> translated = new ArrayList<>();
        for (Sort.Order order : sort) {
            String property = ALLOWED.get(order.getProperty());
            if (property == null) {
                // 타입명·필드명은 싣지 않고, 되싣는 값은 EchoedInput 을 통과시킨다. [D-025]
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "정렬할 수 없는 속성입니다: %s (허용: %s)"
                                .formatted(EchoedInput.safe(order.getProperty()), ALLOWED_KEYS));
            }
            translated.add(order.withProperty(property));
        }

        // 무정렬이면 이 줄이 기본 순서(최신순)다. 이미 id 로 정렬하면 붙이지 않는다.
        if (translated.stream().noneMatch(order -> TIE_BREAKER.equals(order.getProperty()))) {
            translated.add(Sort.Order.desc(TIE_BREAKER));
        }

        Sort entitySort = Sort.by(translated);
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), entitySort)
                : Pageable.unpaged(entitySort);
    }
}
