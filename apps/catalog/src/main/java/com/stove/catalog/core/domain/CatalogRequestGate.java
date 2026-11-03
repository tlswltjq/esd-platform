package com.stove.catalog.core.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A stable lock target also exists before the original submission has arrived. */
@Entity
@Table(name = "catalog_request_gate")
public class CatalogRequestGate {
    @Id private Long requestId;
}
