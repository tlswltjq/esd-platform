package com.stove.payment.core.domain;

/** A reversible platform sale restriction before Order has admitted this approval. */
public class OrderSaleRestricted extends RuntimeException {
    public OrderSaleRestricted() { super("Order admission is currently restricted by the platform"); }
}
