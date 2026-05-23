package com.zzk.orderbook.model;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Immutable {@link PriceLevel} snapshot used by the default
 * convenience APIs on {@code L3OrderBook}. Built from a {@link MutableLevel}
 * holder plus a materialized list of orders walked once via
 * {@code forEachOrderAtPrice}.
 */
public final class ImmutablePriceLevel implements PriceLevel {

    private final Side side;
    private final long price;
    private final int orderCount;
    private final long totalQuantity;
    private final List<Order> orders;

    public ImmutablePriceLevel(Side side, long price, int orderCount, long totalQuantity,
                               List<Order> orders) {
        this.side = side;
        this.price = price;
        this.orderCount = orderCount;
        this.totalQuantity = totalQuantity;
        this.orders = Collections.unmodifiableList(orders);
    }

    @Override
    public long price() {
        return price;
    }

    @Override
    public Side side() {
        return side;
    }

    @Override
    public int orderCount() {
        return orderCount;
    }

    @Override
    public long totalQuantity() {
        return totalQuantity;
    }

    @Override
    public Collection<Order> orders() {
        return orders;
    }
}
