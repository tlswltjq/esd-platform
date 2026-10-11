package com.stove.download.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * productCode ↔ productId 매핑 사본.
 * 빌드는 productCode(스튜디오 기준), 라이선스는 productId(카탈로그 기준)로 오기 때문에
 * 다운로드 시점에 두 키를 잇는 참조가 필요하다. catalog 의 ProductChanged 로 유지된다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "product_ref")
public class ProductRef {

    /** 문서 키로 사용하는 상품 코드. */
    @Id
    private String id;

    /** Catalog 판매 상품 식별자. */
    @Indexed
    private Long productId;

    /** 조회 화면에 표시할 이름. */
    private String name;

    /** Catalog에서 전달받은 상품 판매 상태. */
    private String status;

    /** Catalog가 현재 노출하는 공개 릴리스. 매니페스트와 일치해야 티켓을 발급한다. */
    private Long releaseId;
}
