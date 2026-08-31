package com.stove.store.core.domain;

import com.stove.common.event.payload.ProductChangedEvent;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 검색 색인 문서. <b>문서 ID = productId 라 자연 멱등</b>이므로 별도 Inbox 테이블이 없다.
 * docs/code-notes.md
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "stove-products")
public class ProductDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String productCode;

    /** 한국어 형태소 분석기가 없는 로컬 환경을 고려해 text + keyword 조합만 사용 */
    @Field(type = FieldType.Text)
    private String name;

    @Field(type = FieldType.Long)
    private Long sellerId;

    @Field(type = FieldType.Long)
    private Long price;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String ratingCode;

    @Field(type = FieldType.Date, format = DateFormat.date_optional_time)
    private Instant indexedAt;

    public static ProductDocument from(ProductChangedEvent event) {
        return ProductDocument.builder()
                .id(String.valueOf(event.productId()))
                .productCode(event.productCode())
                .name(event.name())
                .sellerId(event.sellerId())
                .price(event.price())
                .currency(event.currency())
                .status(event.status())
                .ratingCode(event.ratingCode())
                .indexedAt(Instant.now())
                .build();
    }

    public boolean onSale() {
        return "ON_SALE".equals(status);
    }
}
