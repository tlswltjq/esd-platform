package com.stove.settlement.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 수수료 정책 — <b>정산 로직의 첫 분기</b>.
 *
 * <p>이 클래스는 무테스트였고, {@code recordSale} 경유로도 닿지 않았다.
 * 테스트가 판매자 ID 로 1001·1002 를 쓰는데 자체 판매 기준값은 1 이라, SELF 분기가
 * 한 번도 실행되지 않았기 때문이다. 기존 {@code selfSaleHasNoFee} 는
 * {@code SaleType.SELF} 와 {@code ZERO} 를 직접 넘겨 산술만 확인하므로
 * <b>{@code saleTypeOf}/{@code feeRateOf} 를 뒤집어도 깨지지 않았다.</b>
 *
 * <p>여기서 검증하는 것은 산술이 아니라 <b>분기 자체</b>다.
 */
class FeePolicyTest {

    private static final long SELF_SELLER_ID = 1L;
    private static final BigDecimal PARTNER_FEE_RATE = new BigDecimal("0.3000");

    private final FeePolicy feePolicy =
            new FeePolicy(new SettlementProperties(SELF_SELLER_ID, PARTNER_FEE_RATE));

    @Test
    @DisplayName("자체 판매자 ID 는 SELF 로 분류된다")
    void selfSellerIsClassifiedAsSelf() {
        assertThat(feePolicy.saleTypeOf(SELF_SELLER_ID)).isEqualTo(SaleType.SELF);
    }

    @Test
    @DisplayName("그 외 판매자는 전부 PARTNER 로 분류된다")
    void anyOtherSellerIsPartner() {
        assertThat(feePolicy.saleTypeOf(1001L)).isEqualTo(SaleType.PARTNER);
        assertThat(feePolicy.saleTypeOf(2L)).isEqualTo(SaleType.PARTNER);
        assertThat(feePolicy.saleTypeOf(0L)).isEqualTo(SaleType.PARTNER);
    }

    @Test
    @DisplayName("SELF 는 수수료가 0 이다")
    void selfSaleHasZeroFeeRate() {
        assertThat(feePolicy.feeRateOf(SaleType.SELF))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PARTNER 는 설정된 중개 수수료율을 쓴다")
    void partnerSaleUsesConfiguredRate() {
        assertThat(feePolicy.feeRateOf(SaleType.PARTNER))
                .as("설정값이 아니라 상수를 돌려주면 요율 변경이 반영되지 않는다")
                .isEqualByComparingTo(PARTNER_FEE_RATE);
    }

    @Test
    @DisplayName("수수료율은 설정에서 오지 하드코딩이 아니다")
    void feeRateFollowsConfiguration() {
        // 요율을 바꾼 정책은 바꾼 값을 그대로 써야 한다. 이 단언이 없으면
        // feeRateOf 가 0.3000 을 상수로 박아도 위 테스트가 통과한다.
        FeePolicy tenPercent =
                new FeePolicy(new SettlementProperties(SELF_SELLER_ID, new BigDecimal("0.1000")));

        assertThat(tenPercent.feeRateOf(SaleType.PARTNER))
                .isEqualByComparingTo(new BigDecimal("0.1000"));
    }

    @Test
    @DisplayName("자체 판매자 기준도 설정에서 온다")
    void selfSellerIdFollowsConfiguration() {
        FeePolicy otherSelf =
                new FeePolicy(new SettlementProperties(99L, PARTNER_FEE_RATE));

        assertThat(otherSelf.saleTypeOf(99L)).isEqualTo(SaleType.SELF);
        assertThat(otherSelf.saleTypeOf(SELF_SELLER_ID)).isEqualTo(SaleType.PARTNER);
    }

    @Test
    @DisplayName("설정이 비어 있으면 자체=1, 수수료=30% 로 떨어진다")
    void missingConfigurationFallsBackToDefaults() {
        // 이 기본값은 설정 누락 배포에서 조용히 쓰인다 — 금전 규칙이므로 명시적으로 고정해 둔다.
        SettlementProperties defaults = new SettlementProperties(null, null);

        assertThat(defaults.selfSellerId()).isEqualTo(1L);
        assertThat(defaults.partnerFeeRate()).isEqualByComparingTo(new BigDecimal("0.3000"));
    }
}
