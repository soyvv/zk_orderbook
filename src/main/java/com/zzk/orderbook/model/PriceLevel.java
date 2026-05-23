package com.zzk.orderbook.model;

import java.util.Collection;

/**
 * One price bucket containing individual L3 orders in arrival order.
 * Concrete storage (LinkedHashMap vs array+index) decided by implementation.
 */
public interface PriceLevel {

    long price();

    Side side();

    int orderCount();

    long totalQuantity();

    Collection<Order> orders();
}
