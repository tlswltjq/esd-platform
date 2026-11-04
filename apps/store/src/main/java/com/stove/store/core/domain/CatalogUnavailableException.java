package com.stove.store.core.domain;

public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException() { super("Catalog is temporarily unavailable"); }
}
