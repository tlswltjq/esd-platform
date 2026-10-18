package com.stove.license.core.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaidOrderTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "orderNo", "requestEventId", "paymentResultEventId",
            "paymentId", "buyerSubject", "productId", "productCode", "title", "contentVersion", "amount", "currency"})
    void rejectsMissingFacts(String field) {
        ObjectNode body = json.valueToTree(paid());
        body.remove(field);
        assertThatThrownBy(() -> json.treeToValue(body, PaidOrder.class)).hasRootCauseInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "requestEventId", "paymentResultEventId", "paymentId", "buyerSubject", "title"})
    void rejectsBlankAndOverlongText(String field) {
        for (String value : new String[]{" ", "x".repeat(201)}) {
            ObjectNode body = json.valueToTree(paid());
            body.put(field, value);
            assertThatThrownBy(() -> json.treeToValue(body, PaidOrder.class)).hasRootCauseInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"productId", "contentVersion", "amount"})
    void rejectsNonpositiveNumbers(String field) {
        for (long value : new long[]{0, -1}) {
            ObjectNode body = json.valueToTree(paid());
            body.put(field, value);
            assertThatThrownBy(() -> json.treeToValue(body, PaidOrder.class)).hasRootCauseInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsBadCodeCurrencyOrderAndNanosecondPrecision() {
        for (String[] invalid : new String[][]{{"productCode", "bad/code"}, {"productCode", "x".repeat(51)},
                {"currency", "USD"}, {"orderNo", "opaque-id"}, {"occurredAt", "2026-10-17T00:00:00.123456789Z"}}) {
            ObjectNode body = json.valueToTree(paid());
            body.put(invalid[0], invalid[1]);
            assertThatThrownBy(() -> json.treeToValue(body, PaidOrder.class)).hasRootCauseInstanceOf(BusinessException.class);
        }
    }

    @Test
    void licenseKeepsTheCompletePurchaseFact() {
        PaidOrder p = paid();
        License license = License.grant(p);
        assertThat(license.getLicenseId()).isNotBlank();
        assertThat(license.getTitle()).isEqualTo(p.title());
        assertThat(license.paidOrder()).isEqualTo(p);
        assertThat(license.getReceivedAt()).isEqualTo(license.getAcquiredAt());
        license.verify(p);
        assertThatThrownBy(() -> license.verify(paid("other", 1L, "Game-1"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> license.verify(paid("usr_buyer", 1L, "Game-2"))).isInstanceOf(BusinessException.class);
    }
}
