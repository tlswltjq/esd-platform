package com.stove.payment.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.payload.OrderLine;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * 결제 시점의 주문 항목 스냅샷을 JSON 컬럼으로 저장한다.
 * 결제/라이선스/정산 이벤트에 실려야 하는 값이라 payment 가 자체 사본을 갖는다(서비스 자율성).
 */
@Converter
public class OrderLinesConverter implements AttributeConverter<List<OrderLine>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<OrderLine>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<OrderLine> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (Exception e) {
            throw new IllegalStateException("주문 항목 직렬화 실패", e);
        }
    }

    @Override
    public List<OrderLine> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("주문 항목 역직렬화 실패", e);
        }
    }
}
