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
 * <p><b>왜 core.domain 인가</b> — 표의 오른쪽 끝이 여기 있다. 응답이 내보내는 이름과
 * {@link Product} 의 속성을 {@code ProductSortKeysTest} 가 전수로 대조하므로,
 * 어느 쪽 필드를 지워도 이 표가 조용히 썩지 않는다.
 *
 * <p><b>정렬은 항상 결정적이다</b> [D-025] — 표를 통과한 뒤 마지막 절로 {@code id desc} 가
 * 붙는다. {@code price}·{@code name} 은 유일하지 않아서, 동값이 한 페이지를 넘으면
 * 동순위의 순서가 statement 마다 달라진다 — 같은 상품이 두 페이지에 나오고 어떤 상품은
 * 어느 페이지에도 안 나온다. 정렬을 주지 않은 요청도 같은 이유로 {@code id desc} 를 받는다.
 *
 * <p>{@code id} 를 <b>허용 키로는 거절하면서</b> 정렬 절로는 항상 붙이는 것이 모순이 아닌 이유 —
 * 허용 목록은 <b>계약의 이름</b>을 정하는 것이고 꼬리표는 <b>결정성</b>을 만드는 것이다.
 * 부르는 쪽은 여전히 {@code id} 라는 이름을 쓸 수 없고, 알 필요도 없다.
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

    /** 오류 메시지가 나열하는 허용 키. 표가 불변이므로 요청마다 다시 잇지 않는다. */
    private static final String ALLOWED_KEYS = String.join(", ", ALLOWED.keySet());

    /**
     * 마지막에 붙는 꼬리표 속성. 유일하고 값이 변하지 않는 것이어야 한다.
     *
     * <p>방향은 항상 {@code desc} 로 고정한다. 앞 절의 방향을 따라가게 하면 "무엇이 마지막에
     * 붙는가"를 한 줄로 적을 수 없고, 동값 구간 안의 순서가 앞 절에 따라 뒤집힌다.
     */
    private static final String TIE_BREAKER = "id";

    /**
     * 정렬 절 개수 상한.
     *
     * <p>표보다 많은 절은 반드시 같은 키를 두 번 쓴 것이고, 두 번째부터는 순서에 영향이 없다.
     * 상한이 필요한 이유는 스프링이 {@code sort} 파라미터의 <b>반복 횟수</b>를 막지 않기 때문이다 —
     * {@code PageableHandlerMethodArgumentResolver} 가 상한을 두는 것은 {@code size} 뿐이라,
     * 인증 없는 공개 경로에서 수백 개 절짜리 {@code ORDER BY} 를 만들 수 있다.
     */
    private static final int MAX_ORDERS = ALLOWED.size();

    private ProductSort() {
    }

    /** 허용 키 → 엔티티 속성. 전수 대조 테스트가 이 표를 그대로 읽는다. */
    public static Map<String, String> allowedKeys() {
        return ALLOWED;
    }

    /**
     * 정렬 키를 검사하고 엔티티 속성 이름으로 옮긴 {@link Pageable} 을 돌려준다.
     * 마지막에는 항상 {@code id desc} 가 붙는다 — 정렬을 주지 않은 요청도 마찬가지다.
     *
     * <p>컨트롤러가 아니라 서비스가 이것을 부르는 이유는 D-019·D-020 과 같다 —
     * 어댑터에만 두면 그 경로 하나만 지켜지고, 어댑터는 늘어난다.
     *
     * @throws BusinessException 허용 목록에 없는 정렬 키가 있거나 절이 너무 많으면
     *         {@code INVALID_REQUEST}
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
                // 엔티티 타입명이나 필드명은 싣지 않는다. 인증 없는 공개 경로라
                // 오류 메시지가 내부 구조를 알려 주는 통로가 된다.
                // 되받아 싣는 값은 EchoedInput 을 통과시킨다 — 이 메시지는 응답이자 로그다(D-025).
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "정렬할 수 없는 속성입니다: %s (허용: %s)"
                                .formatted(EchoedInput.safe(order.getProperty()), ALLOWED_KEYS));
            }
            translated.add(order.withProperty(property));
        }

        // 무정렬이면 이 줄이 만드는 정렬이 곧 기본 순서(최신순)다.
        // 이미 id 로 정렬하는 요청에는 붙이지 않는다 — 같은 속성의 두 번째 절은 아무 일도 하지 않는다.
        if (translated.stream().noneMatch(order -> TIE_BREAKER.equals(order.getProperty()))) {
            translated.add(Sort.Order.desc(TIE_BREAKER));
        }

        Sort entitySort = Sort.by(translated);
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), entitySort)
                : Pageable.unpaged(entitySort);
    }
}
