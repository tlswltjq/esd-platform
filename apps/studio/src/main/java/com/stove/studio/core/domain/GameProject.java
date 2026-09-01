package com.stove.studio.core.domain;

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

    /**
     * 심의 결과 반영. <b>신청한 건에 대해서만</b> 의미가 있다.
     *
     * <p>{@link #submit()} 과 달리 예외를 던지지 않는다 — 이 경로는 컨슈머가 부른다.
     * 지각 이벤트에 예외를 던지면 리스너 밖으로 나가 그 파티션이 멈추는데,
     * 상태는 재시도로 바뀌지 않으므로 영원히 풀리지 않는다.
     *
     * @return 상태가 실제로 바뀌었으면 true
     */
    public boolean approve(String ratingCode) {
        if (status != ProjectStatus.SUBMITTED) {
            return false;
        }
        this.status = ProjectStatus.APPROVED;
        this.ratingCode = ratingCode;
        this.rejectReason = null;
        return true;
    }

    /** @see #approve(String) 지각·중복 이벤트를 예외 없이 흘려보내는 이유는 같다. */
    public boolean reject(String reason) {
        if (status != ProjectStatus.SUBMITTED) {
            return false;
        }
        this.status = ProjectStatus.REJECTED;
        this.rejectReason = reason;
        return true;
    }

    public void requireOwner(Long sellerId) {
        if (!this.sellerId.equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
