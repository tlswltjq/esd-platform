package com.stove.settlement.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.port.CatalogOwners;
import org.springframework.web.client.RestClient;

public class CatalogOwnerHttpClient implements CatalogOwners {
    private final RestClient client;
    public CatalogOwnerHttpClient(RestClient client) { this.client=client; }
    @Override public ProductOwner require(String code) {
        try {
            JsonNode body=HttpValues.get(client,"/internal/v1/products/{code}/owner",code);
            if(!body.path("success").isBoolean() || !body.path("success").booleanValue() || !body.path("data").isObject())
                throw HttpValues.unavailable();
            JsonNode data=body.path("data");
            ProductOwner owner=new ProductOwner(HttpValues.number(data,"productId"),HttpValues.text(data,"productCode"),HttpValues.text(data,"ownerSubject"));
            if(!code.equals(owner.productCode())) throw HttpValues.unavailable();
            return owner;
        } catch(BusinessException | IllegalArgumentException failure) { throw HttpValues.unavailable(); }
    }
}
