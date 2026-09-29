package com.stove.store.core.domain;

import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.event.payload.PromotionWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.payload.StorefrontSnapshot;
import java.time.Instant;
import java.util.List;
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
 *
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "stove-products")
public class ProductDocument {

    private static final ObjectMapper PROMOTION_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<PromotionWindow>> PROMOTION_TYPE = new TypeReference<>() {};

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

    @Field(type = FieldType.Text, index = false)
    private String promotionScheduleJson;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Boolean)
    private Boolean visible;

    @Field(type = FieldType.Keyword)
    private String ratingCode;

    @Field(type = FieldType.Long)
    private Long releaseId;

    @Field(type = FieldType.Long)
    private Long buildId;

    @Field(type = FieldType.Long)
    private Long metadataRevision;

    @Field(type = FieldType.Keyword)
    private String productKind;

    @Field(type = FieldType.Keyword)
    private String parentProductCode;

    @Field(type = FieldType.Keyword)
    private String editionName;

    @Field(type = FieldType.Keyword)
    private List<String> bundleProductCodes;

    @Field(type = FieldType.Object)
    private StorefrontSnapshot storefront;

    @Field(type = FieldType.Long)
    private long projectionVersion;

    @Field(type = FieldType.Date, format = DateFormat.date_optional_time)
    private Instant indexedAt;

    public static ProductDocument from(ProductChangedEvent event) {
        return ProductDocument.builder()
                .id(String.valueOf(event.productId()))
                .productCode(event.productCode())
                .name(event.name())
                .sellerId(event.sellerId())
                .price(event.price())
                .promotionScheduleJson(writePromotions(event.promotions()))
                .currency(event.currency())
                .status(event.status())
                .visible(event.releaseId() != null && ("ON_SALE".equals(event.status())
                        || "APPROVED".equals(event.status()) || "SUSPENDED".equals(event.status())))
                .ratingCode(event.ratingCode())
                .releaseId(event.releaseId())
                .buildId(event.buildId())
                .metadataRevision(event.metadataRevision())
                .productKind(event.productKind())
                .parentProductCode(event.parentProductCode())
                .editionName(event.editionName())
                .bundleProductCodes(event.bundleProductCodes())
                .storefront(event.storefront())
                .projectionVersion(event.projectionVersion())
                .indexedAt(Instant.now())
                .build();
    }

    public List<PromotionWindow> promotions() {
        return parsePromotions(promotionScheduleJson);
    }

    public static List<PromotionWindow> parsePromotions(String json) {
        if (json == null) return List.of();
        try {
            return PROMOTION_MAPPER.readValue(json, PROMOTION_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("색인된 행사 일정을 읽을 수 없습니다.", e);
        }
    }

    private static String writePromotions(List<PromotionWindow> promotions) {
        try {
            return PROMOTION_MAPPER.writeValueAsString(promotions == null ? List.of() : promotions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("행사 일정을 색인할 수 없습니다.", e);
        }
    }

    public boolean onSale() {
        return "ON_SALE".equals(status);
    }
}
