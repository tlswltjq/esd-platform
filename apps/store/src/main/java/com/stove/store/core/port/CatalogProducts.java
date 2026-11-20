package com.stove.store.core.port;

import com.stove.common.contract.payload.CatalogProductSnapshot;
import java.util.List;

public interface CatalogProducts {
    List<String> codes(int page);
    CatalogProductSnapshot detail(String code);
}
