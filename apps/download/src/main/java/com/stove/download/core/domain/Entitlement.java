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

    public Entitlement revoke() {
        this.active = false;
        this.revokedAt = Instant.now();
        return this;
    }
}
