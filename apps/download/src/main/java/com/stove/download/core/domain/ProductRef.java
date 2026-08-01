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

    /** productCode */
    @Id
    private String id;

    @Indexed
    private Long productId;

    private String name;

    private String status;
}
