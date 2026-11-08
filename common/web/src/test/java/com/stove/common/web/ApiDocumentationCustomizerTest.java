package com.stove.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiDocumentationCustomizerTest {
    private final ApiDocumentationCustomizer customizer =
            new ApiDocumentationCustomizer("payment", "http://auth/authorize", "http://auth/token");

    /** OpenAPI 3.1: `$ref` + `type: "null"` together allow nothing, so a nullable object reference must be `anyOf`. */
    @Test
    void aNullableObjectReferenceAcceptsTheObjectOrNull() {
        Schema<Object> reference = new Schema<>().$ref("#/components/schemas/Notice");
        reference.setTypes(Set.of("null"));
        reference.setDescription("보류 안내. 그 밖에는 null입니다.");
        Schema<Object> response = new Schema<>().addProperty("reviewNotice", reference);
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("PaymentResponse", response));

        customizer.customise(openApi);

        Schema<?> customised = openApi.getComponents().getSchemas().get("PaymentResponse");
        Schema<?> property = customised.getProperties().get("reviewNotice");
        assertThat(property.get$ref()).isNull();
        assertThat(property.getTypes()).isNull();
        assertThat(property.getDescription()).isEqualTo("보류 안내. 그 밖에는 null입니다.");
        assertThat(property.getAnyOf()).hasSize(2);
        Schema<?> object = property.getAnyOf().get(0);
        Schema<?> absent = property.getAnyOf().get(1);
        assertThat(object.get$ref()).isEqualTo("#/components/schemas/Notice");
        assertThat(absent.getTypes()).containsExactly("null");
    }

    @Test
    void requiredReferencesAndNullableScalarsAreLeftAsGenerated() {
        Schema<Object> reference = new Schema<>().$ref("#/components/schemas/Notice");
        Schema<Object> scalar = new Schema<>();
        scalar.setTypes(Set.of("string", "null"));
        Schema<Object> response = new Schema<>().addProperty("notice", reference).addProperty("source", scalar);
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Response", response));

        customizer.customise(openApi);

        Schema<?> schema = openApi.getComponents().getSchemas().get("Response");
        assertThat(schema.getProperties().get("notice").get$ref()).isEqualTo("#/components/schemas/Notice");
        assertThat(schema.getProperties().get("notice").getAnyOf()).isNull();
        assertThat(schema.getProperties().get("source").getTypes()).containsExactlyInAnyOrder("string", "null");
    }
}
