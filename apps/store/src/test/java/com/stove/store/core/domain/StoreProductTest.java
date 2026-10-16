package com.stove.store.core.domain;

import static com.stove.store.StoreFixtures.*;
import static org.assertj.core.api.Assertions.*;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class StoreProductTest {
    @Test void repeatedAndOlderSnapshotsCannotRevertCurrentPublication() {
        CatalogProductSnapshot old = product(1, 1, "Old", 1000);
        CatalogProductSnapshot latest = product(1, 3, "Latest", 2000);
        StoreProduct stored = StoreProduct.create(old);
        stored.apply(latest);
        stored.apply(old);
        stored.apply(latest);
        assertThat(stored.snapshot()).isEqualTo(latest);
    }
    @Test void sameVersionConflictsPreserveOriginalSnapshot() {
        CatalogProductSnapshot original = product(1, 1, "Original", 1000);
        StoreProduct stored = StoreProduct.create(original);
        assertThatThrownBy(() -> stored.apply(product(1, 1, "Changed", 1000)))
                .isInstanceOf(BusinessException.class);
        assertThat(stored.snapshot()).isEqualTo(original);
    }
    @Test void identityCannotChangeEvenInANewerVersion() {
        CatalogProductSnapshot original = product(1, 1, "Original", 1000);
        StoreProduct stored = StoreProduct.create(original);
        assertThatThrownBy(() -> stored.apply(product(2, 2, "Changed", 1000)))
                .isInstanceOf(BusinessException.class);
        assertThat(stored.snapshot()).isEqualTo(original);
    }
}
