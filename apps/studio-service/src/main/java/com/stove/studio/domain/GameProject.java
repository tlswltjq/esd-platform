package com.stove.studio.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 창작자의 게임 프로젝트(셀프 퍼블리싱 단위).
 * 상품 메타데이터의 원본이며, 심의를 통과하면 catalog 가 이 값으로 상품 마스터를 만든다.
 */
@Entity
@Getter
@Table(name = "game_project")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameProject extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 창작자가 정하는 상품 코드. 서비스 경계를 넘는 자연 키로 쓰인다. */
    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(nullable = false, length = 200)
    private String title;

    /** 스튜디오 판매자 ID (자체 게임은 1, 입점사는 1001~) */
    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false, length = 3)
    private String currency;

    /** 자체등급분류 대상 여부 */
    @Column(nullable = false)
    private boolean selfRated;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(length = 10)
    private String ratingCode;

    @Column(length = 200)
    private String rejectReason;

    private GameProject(String productCode, String title, Long sellerId, long price, String currency,
                        boolean selfRated) {
        this.productCode = productCode;
        this.title = title;
        this.sellerId = sellerId;
        this.price = price;
        this.currency = currency;
        this.selfRated = selfRated;
        this.status = ProjectStatus.DRAFT;
    }

    public static GameProject create(String productCode, String title, Long sellerId, long price,
                                     String currency, boolean selfRated) {
        return new GameProject(productCode, title, sellerId, price, currency, selfRated);
    }

    public void submit() {
        if (status == ProjectStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 심의 신청된 프로젝트입니다.");
        }
        if (status == ProjectStatus.APPROVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 승인된 프로젝트입니다.");
        }
        this.status = ProjectStatus.SUBMITTED;
        this.rejectReason = null;
    }

    public void approve(String ratingCode) {
        this.status = ProjectStatus.APPROVED;
        this.ratingCode = ratingCode;
        this.rejectReason = null;
    }

    public void reject(String reason) {
        this.status = ProjectStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void requireOwner(Long sellerId) {
        if (!this.sellerId.equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
