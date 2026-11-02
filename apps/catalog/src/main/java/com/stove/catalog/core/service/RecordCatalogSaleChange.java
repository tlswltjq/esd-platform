package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogSaleChange;
import com.stove.catalog.core.domain.CatalogSaleChangeRepository;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class RecordCatalogSaleChange {
    private final CatalogSaleChangeRepository changes;
    private final ReadPublishedProducts read;
    private final OutboxRecorder outbox;
    public void execute(CatalogProduct product, String actor, String authority, String previousStatus,
            boolean previousRestriction, long previousVersion, Instant now) {
        changes.save(CatalogSaleChange.record(product,actor,authority,previousStatus,previousRestriction,previousVersion,now));
        outbox.record("CatalogProduct",product.getProductCode(),CatalogProductPublishedEvent.of(read.internalDetail(product.getProductCode()).publicSnapshot()));
    }
}
