package com.stove.order.core.domain;

/** Shared identifier formats, independent of any use-case input. */
public final class OrderValues {
    public static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    public static final String PRODUCT_CODE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}";

    private OrderValues() {}
}
