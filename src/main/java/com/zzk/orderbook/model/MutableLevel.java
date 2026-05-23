package com.zzk.orderbook.model;

/**
 * Caller-owned holder populated by {@code L3OrderBook.getByPrice(side, price, out)}
 * and {@code L3OrderBook.getByLevel(side, levelIndex, out)}. Allocation-free
 * counterpart to {@link PriceLevel} for aggregate-only lookups.
 *
 * <p>Same usage pattern as {@link MutableOrder}: construct once, reuse across
 * calls, check the boolean return before reading fields.
 */
public final class MutableLevel {

    public Side side;
    public long price;
    public int orderCount;
    public long totalQuantity;

    public MutableLevel() {
    }

    public void clear() {
        side = null;
        price = 0L;
        orderCount = 0;
        totalQuantity = 0L;
    }
}
