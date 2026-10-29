package com.stove.payment.adapter.order;

import com.stove.payment.core.domain.OrderAdmissionFailureReceipt;
import com.stove.payment.core.domain.OrderAdmissionFailure;
import com.stove.payment.core.domain.OrderAdmissionReceipt;
import com.stove.payment.core.domain.OrderAdmissionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.OrderPaymentAdmissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpOrderPaymentAdmissions implements OrderPaymentAdmissions {
    private final RestClient client;
    public HttpOrderPaymentAdmissions(RestClient client) { this.client = client; }
    @Override public Optional<OrderAdmissionReceipt> admit(OrderAdmissionRequest request) {
        Map<String, Object> body = Map.of("admissionId", request.admissionId(), "requestEventId", request.requestEventId(),
                "paymentId", request.purchase().paymentId(), "buyerSubject", request.buyerSubject(),
                "amount", request.purchase().amount(), "currency", request.purchase().currency());
        JsonNode data = post("/internal/v1/orders/{orderNo}/payment-admissions", request.purchase().orderNo(), null, body, true);
        if (data == null) return Optional.empty();
        OrderAdmissionReceipt receipt = new OrderAdmissionReceipt(text(data,"admissionId"), text(data,"orderNo"), text(data,"paymentId"),
                time(data,"admittedAt"), time(data,"expiresAt"));
        receipt.requireRequest(request);
        return Optional.of(receipt);
    }
    @Override public OrderAdmissionFailureReceipt fail(OrderAdmissionFailure request) {
        JsonNode data = post("/internal/v1/orders/{orderNo}/payment-admissions/{admissionId}/failure",
                request.admission().purchase().orderNo(), request.admission().admissionId(),
                Map.of("paymentId", request.admission().purchase().paymentId(), "supplierAttemptId", request.supplierAttemptId(),
                        "failedAt", request.failedAt().toString()), false);
        OrderAdmissionFailureReceipt receipt = new OrderAdmissionFailureReceipt(text(data,"admissionId"), text(data,"orderNo"), text(data,"paymentId"),
                time(data,"closedAt"), text(data,"orderStatus"));
        receipt.requireRequest(request);
        return receipt;
    }
    private JsonNode post(String path, String orderNo, String admissionId, Object body, boolean allowExpiry) {
        try {
            return client.post().uri(path, admissionId == null ? new Object[]{orderNo} : new Object[]{orderNo,admissionId})
                    .contentType(MediaType.APPLICATION_JSON).body(body).exchange((outgoing, response) -> {
                        JsonNode result = response.bodyTo(JsonNode.class);
                        if (allowExpiry && response.getStatusCode().value() == 409 && result != null
                                && "ORDER_EXPIRED".equals(result.path("error").path("code").asText())) return null;
                        if (response.getStatusCode().value() == 409)
                            throw new BusinessException(ErrorCode.CONFLICT, "Order approval evidence conflicts");
                        if (!response.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("Order approval service unavailable");
                        if (result == null || !result.path("success").asBoolean() || !result.path("data").isObject())
                            throw new IllegalArgumentException("Invalid Order approval response");
                        return result.path("data");
                    });
        } catch (RestClientException unavailable) { throw new PgUnavailable("Order approval HTTP unavailable"); }
    }
    private static String text(JsonNode data, String field) {
        if (data == null || !data.path(field).isTextual() || data.path(field).asText().isBlank())
            throw new IllegalArgumentException("Missing Order receipt field");
        return data.path(field).asText();
    }
    private static Instant time(JsonNode data, String field) {
        try { return Instant.parse(text(data,field)); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("Invalid Order receipt time"); }
    }
}
