package com.stove.review.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 등급분류 심의 신청.
 *
 * <p>자체등급분류사업자 경로({@code selfRated=true})는 게임물관리위원회 접수 없이 내부 심사로 처리되고,
 * 그 외에는 게임위 접수 번호({@code boardTicketId})를 받은 뒤 심사에 들어간다.
 * 상태 전이는 {@link ReviewStatus#canTransitTo} 로만 허용된다.
 */
@Entity
@Getter
@Table(name = "review_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    /** studio 프로젝트와 catalog 상품을 잇는 자연 키 */
    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean selfRated;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    /** 게임위 접수 번호(자체등급분류 건은 null) */
    @Column(length = 50)
    private String boardTicketId;

    @Column(length = 10)
    private String ratingCode;

    @Column(length = 30)
    private String rejectReasonCode;

    @Column(length = 200)
    private String rejectReason;

    private Instant closedAt;

    private ReviewRequest(Long gameId, String productCode, String title, Long sellerId,
                          long price, String currency, boolean selfRated) {
        this.gameId = gameId;
        this.productCode = productCode;
        this.title = title;
        this.sellerId = sellerId;
        this.price = price;
        this.currency = currency;
        this.selfRated = selfRated;
        this.status = ReviewStatus.REQUESTED;
    }

    public static ReviewRequest received(Long gameId, String productCode, String title, Long sellerId,
                                         long price, String currency, boolean selfRated) {
        return new ReviewRequest(gameId, productCode, title, sellerId, price, currency, selfRated);
    }

    /** 반려 후 재신청 */
    public void reopen(String title, long price) {
        transitTo(ReviewStatus.REQUESTED);
        this.title = title;
        this.price = price;
        this.rejectReason = null;
        this.rejectReasonCode = null;
    }

    public void startReview(String boardTicketId) {
        transitTo(ReviewStatus.IN_REVIEW);
        this.boardTicketId = boardTicketId;
    }

    public void approve(String ratingCode) {
        transitTo(ReviewStatus.APPROVED);
        this.ratingCode = ratingCode;
        this.closedAt = Instant.now();
    }

    public void reject(String reasonCode, String reason) {
        transitTo(ReviewStatus.REJECTED);
        this.rejectReasonCode = reasonCode;
        this.rejectReason = reason;
        this.closedAt = Instant.now();
    }

    private void transitTo(ReviewStatus next) {
        if (!status.canTransitTo(next)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "허용되지 않은 심의 상태 전이: %s → %s".formatted(status, next));
        }
        this.status = next;
    }
}
