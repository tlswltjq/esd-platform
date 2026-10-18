package com.stove.settlement.core.port;

import com.stove.settlement.core.domain.ProductOwner;

public interface CatalogOwners { ProductOwner require(String productCode); }
