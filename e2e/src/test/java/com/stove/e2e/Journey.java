package com.stove.e2e;

import static org.assertj.core.api.Assertions.fail;

import java.util.Map;

/**
 * 한 저니가 네 장을 이어 간다 — 트랙 A 가 만든 상품을 B 가 사고, B 가 만든 주문을 C 가 지급·환불한다.
 * 장 사이를 건너는 값은 인스턴스가 아니라 <b>시나리오</b>의 것이라 여기 static 으로 둔다
 * (테스트 JVM 하나, {@code maxParallelForks = 1}).
 *
 * <h2>건너뛴 것을 통과로 읽지 않는다</h2>
 *
 * <p>셸이 {@code require} 와 {@code EXPECTED_CHECKS} 로 막던 것이 <b>"통과처럼 읽히는 미실행"</b>
 * 이었다. 상품을 못 찾으면 트랙 B·C·3-B 38건이 통째로 빠지는데 요약은 "실패 1건" 만 보여줬다.
 *
 * <p>JUnit 으로 옮기면서 그 함정을 새 언어로 다시 파지 않는다 — <b>{@code Assumptions} 를 쓰지 않는다.</b>
 * 선행 단계가 값을 만들지 못했으면 뒤 장은 <i>건너뛴 것(skipped)</i> 이 아니라 <b>실패</b>다.
 * 스킵은 리포트에서 초록 옆에 조용히 앉지만, 실패는 이유를 말하며 빨개진다.
 */
final class Journey {

    /** settlement 의 {@code self-seller-id}(1) 가 아니므로 입점(PARTNER) 판매다 — 수수료 30% 가 붙는다. */
    static final long SELLER = 1001L;

    static final int PRICE = 18_000;
    /** {@code partner-fee-rate} 기본값 0.3000. 셸이 그랬듯 기대값을 스스로 계산해 둔다. */
    static final int FEE = PRICE * 30 / 100;
    static final int NET = PRICE - FEE;

    /**
     * 실행마다 다른 값. <b>스택과 볼륨이 재사용된다</b> — 원격 스택은 계속 떠 있고 `down` 도 볼륨을
     * 남기므로, 고정 코드를 쓰면 두 번째 실행부터 이전 실행의 데이터와 섞인다.
     */
    private static final long STAMP = System.currentTimeMillis() / 1000;

    static final String PRODUCT_CODE = "GAME-E2E-" + STAMP;
    static final long MEMBER = STAMP % 1_000_000;
    /** 미보유 회원. 다운로드 권한이 소유 검사에 걸리는지 보려면 사지 않은 사람이 하나 필요하다. */
    static final long OTHER_MEMBER = MEMBER + 1;

    /** 승인 경로의 PG 거래번호. 거절 경로는 사전등록이 돌려준 값을 써야 한다 — {@link #failPgTxId()}. */
    static final String PG_TX = "PG-E2E-" + STAMP;

    /**
     * PG 멱등키. <b>같은 접미사면 같은 키가 나온다</b> — 중복 콜백 흡수를 확인하려면
     * 트랙 C 가 트랙 B 와 똑같은 키로 다시 보낼 수 있어야 한다.
     */
    static String idempotencyKey(String suffix) {
        return "IDEM-%s-%d".formatted(suffix, STAMP);
    }

    // ── 장 사이를 건너는 값 ────────────────────────────────────────
    private static Long gameId;
    private static Long productId;
    private static String orderNo;
    private static String failOrderNo;
    private static String failPgTxId;

    private Journey() {
    }

    static void gameId(Long value) {
        gameId = value;
    }

    static long gameId() {
        return require(gameId, "gameId", "1장 트랙 A 의 프로젝트 생성");
    }

    static void productId(Long value) {
        productId = value;
    }

    static long productId() {
        return require(productId, "productId", "1장 트랙 A 의 상품 마스터 생성");
    }

    static void orderNo(String value) {
        orderNo = value;
    }

    static String orderNo() {
        return require(orderNo, "orderNo", "2장 트랙 B 의 주문 생성");
    }

    static void failOrderNo(String value) {
        failOrderNo = value;
    }

    static String failOrderNo() {
        return require(failOrderNo, "실패 검증용 orderNo", "3-B 의 주문 생성");
    }

    static void failPgTxId(String value) {
        failPgTxId = value;
    }

    static String failPgTxId() {
        return require(failPgTxId, "실패 검증용 pgTxId", "3-B 의 PG 사전등록");
    }

    /** 회원 헤더. 게이트웨이는 요청 헤더를 그대로 하류로 넘긴다. */
    static Map<String, String> asMember(long memberId) {
        return Map.of("X-Member-Id", String.valueOf(memberId));
    }

    static Map<String, String> asSeller() {
        return Map.of("X-Seller-Id", String.valueOf(SELLER));
    }

    /**
     * 값이 없으면 <b>이 장을 실패시킨다.</b> 스킵이 아니다 — 위 클래스 주석 참고.
     * 메시지에 "누가 만들었어야 하는지" 를 적는다. 실패를 본 사람이 다음에 볼 곳이 그것이다.
     */
    private static <T> T require(T value, String what, String producedBy) {
        if (value == null) {
            fail("선행 단계가 %s 를 만들지 못했다 (%s). 이 장은 건너뛴 것이 아니라 검증되지 않은 것이다."
                    .formatted(what, producedBy));
        }
        return value;
    }
}
