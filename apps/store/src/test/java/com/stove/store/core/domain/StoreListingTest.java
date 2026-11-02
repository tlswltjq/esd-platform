package com.stove.store.core.domain;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class StoreListingTest {
    @Test void olderPayloadWithoutListingUsesAutomaticSalesRuleAndCannotRestoreANewerHiddenCopy() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CatalogProductSnapshot original = product(1, 1, "Title", 1000);
        CatalogProductSnapshot hidden = listing(original, false, 3, "ON_SALE");
        ObjectNode json = mapper.valueToTree(listing(original, true, 2, "STOPPED"));
        json.remove("listed");
        CatalogProductSnapshot historical = mapper.treeToValue(json, CatalogProductSnapshot.class);
        assertThat(historical.listed()).isFalse();
        StoreProduct stored = StoreProduct.create(hidden);
        stored.apply(historical);
        assertThat(stored.snapshot()).isEqualTo(hidden);
    }
    @Test void sameVersionListingConflictIsRejectedAndHigherVersionChangesOnlyVisibility() {
        CatalogProductSnapshot original = product(1, 1, "Title", 1000);
        StoreProduct stored = StoreProduct.create(original);
        CatalogProductSnapshot hidden = listing(original, false, 2, "ON_SALE");
        stored.apply(hidden);
        assertThatThrownBy(() -> stored.apply(listing(original, true, 2, "ON_SALE"))).isInstanceOf(BusinessException.class);
        assertThat(stored.snapshot()).isEqualTo(hidden);
        stored.apply(listing(original, true, 3, "STOPPED"));
        assertThat(stored.snapshot().listed()).isTrue();
    }
    private CatalogProductSnapshot listing(CatalogProductSnapshot p, boolean listed, long version, String saleStatus) {
        return new CatalogProductSnapshot(p.productId(), p.productCode(), p.title(), p.shortDescription(), p.thumbnailUrl(),
                p.price(), p.currency(), p.publishedAt(), p.content(), p.videoUrl(), p.contentVersion(), p.buildVersion(),
                p.updatedAt(), saleStatus, version, listed);
    }
}
