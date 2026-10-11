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

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false)
    private Long gameId;

    /** studio 프로젝트와 catalog 상품을 잇는 자연 키 */
    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    /** 게임 또는 상점 페이지에 표시할 제목. */
    @Column(nullable = false, length = 200)
    private String title;

    /** 판매자를 나타내는 워크스페이스 식별자. */
    @Column(nullable = false)
    private Long sellerId;

    /** 상품 한 개의 가격으로, 최소 화폐 단위의 정수. */
    @Column(nullable = false)
    private long price;

    /** 금액의 통화 코드. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** 자체등급분류 경로로 신청하는지 여부. */
    @Column(nullable = false)
    private boolean selfRated;

    /** 기존 프로젝트 단위 심사의 접수·진행·승인·반려 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    /** 게임위 접수 번호(자체등급분류 건은 null) */
    @Column(length = 50)
    private String boardTicketId;

    /** 확정된 이용 등급 코드. */
    @Column(length = 10)
    private String ratingCode;

    /** 심사 반려 사유를 구분하는 코드. */
    @Column(length = 30)
    private String rejectReasonCode;

    /** 심사 반려 사유에 대한 설명. */
    @Column(length = 200)
    private String rejectReason;

    /** 승인 또는 반려로 심사를 종료한 시각. */
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
