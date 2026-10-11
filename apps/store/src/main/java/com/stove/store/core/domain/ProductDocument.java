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

    /** 할인 일정 JSON을 변환하는 매퍼. */
    private static final ObjectMapper PROMOTION_MAPPER = new ObjectMapper().findAndRegisterModules();
    /** 할인 일정 JSON을 복원할 목록 타입. */
    private static final TypeReference<List<PromotionWindow>> PROMOTION_TYPE = new TypeReference<>() {};

    /** Catalog 상품 ID를 문자열로 저장한 검색 문서 키. */
    @Id
    private String id;

    /** 서비스 간 상품을 연결하는 고유 코드. */
    @Field(type = FieldType.Keyword)
    private String productCode;

    /** 한국어 형태소 분석기가 없는 로컬 환경을 고려해 text + keyword 조합만 사용 */
    @Field(type = FieldType.Text)
    private String name;

    /** 판매자를 나타내는 워크스페이스 식별자. */
    @Field(type = FieldType.Long)
    private Long sellerId;

    /** 상품 한 개의 가격으로, 최소 화폐 단위의 정수. */
    @Field(type = FieldType.Long)
    private Long price;

    /** 가격 계산에 사용할 할인 행사 일정 JSON. */
    @Field(type = FieldType.Text, index = false)
    private String promotionScheduleJson;

    /** 금액의 통화 코드. */
    @Field(type = FieldType.Keyword)
    private String currency;

    /** Catalog에서 전달받은 상품 판매 상태. */
    @Field(type = FieldType.Keyword)
    private String status;

    /** 상점 조회 결과에 노출할 수 있는지 여부. */
    @Field(type = FieldType.Boolean)
    private Boolean visible;

    /** 확정된 이용 등급 코드. */
    @Field(type = FieldType.Keyword)
    private String ratingCode;

    /** 대상 릴리스 식별자. */
    @Field(type = FieldType.Long)
    private Long releaseId;

    /** 배포 파일을 나타내는 빌드 식별자. */
    @Field(type = FieldType.Long)
    private Long buildId;

    /** 상점 소개 자료 판본의 식별자. */
    @Field(type = FieldType.Long)
    private Long metadataRevision;

    /** 기본 게임·체험판·DLC·에디션·번들 구분. */
    @Field(type = FieldType.Keyword)
    private String productKind;

    /** 파생 상품이 연결되는 원본 상품 코드. */
    @Field(type = FieldType.Keyword)
    private String parentProductCode;

    /** 에디션 구분을 위해 표시하는 이름. */
    @Field(type = FieldType.Keyword)
    private String editionName;

    /** 번들에 포함된 상품 코드 목록. */
    @Field(type = FieldType.Keyword)
    private List<String> bundleProductCodes;

    /** 공개된 상점 소개 자료의 스냅샷. */
    @Field(type = FieldType.Object)
    private StorefrontSnapshot storefront;

    /** 상품 사본에 전달하는 변경 순번. */
    @Field(type = FieldType.Long)
    private long projectionVersion;

    /** 검색용 상품 사본을 갱신한 시각. */
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
