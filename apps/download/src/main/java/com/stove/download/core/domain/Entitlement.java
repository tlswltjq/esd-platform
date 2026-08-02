package com.stove.download.core.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * license 이벤트로 유지되는 다운로드 권한 사본.
 * 문서 ID = {@code memberId:productId} 라 지급/회수 이벤트 재전송에도 상태가 수렴한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "entitlement")
public class Entitlement {

    @Id
    private String id;

    @Indexed
    private Long memberId;

    private Long productId;

    private String orderNo;

    private boolean active;

    private Instant grantedAt;

    private Instant revokedAt;

    public static String documentId(Long memberId, Long productId) {
        return memberId + ":" + productId;
    }

    public static Entitlement granted(Long memberId, Long productId, String orderNo) {
        return Entitlement.builder()
                .id(documentId(memberId, productId))
                .memberId(memberId)
                .productId(productId)
                .orderNo(orderNo)
                .active(true)
                .grantedAt(Instant.now())
                .build();
    }

    /**
     * 이 권한이 해당 주문에서 온 것인가.
     *
     * <p>문서 ID 가 {@code memberId:productId} 뿐이라 어느 주문의 권한인지는 이 필드로만 구분된다.
     * 환불 후 재구매하면 같은 문서를 새 주문번호로 덮어쓰므로, 옛 주문의 회수 이벤트가
     * 지각 도착했을 때 새로 산 권한을 거둬가지 않으려면 반드시 대조해야 한다.
     */
    public boolean belongsTo(String orderNo) {
        return this.orderNo != null && this.orderNo.equals(orderNo);
    }

    public Entitlement revoke() {
        this.active = false;
        this.revokedAt = Instant.now();
        return this;
    }
}
