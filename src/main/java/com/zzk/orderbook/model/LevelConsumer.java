package com.zzk.orderbook.model;

/**
 * Visitor receiving aggregate price-level state during best-to-worst walks
 * via {@code L3OrderBook.forEachLevel(side, consumer)}. Fields are emitted as
 * primitives so the call path doesn't allocate.
 *
 * <p>Store the consumer in a long-lived field (or a static singleton) for
 * hot scans — passing a capturing lambda per call defeats the optimization,
 * same pattern as {@link TradeListener}.
 */
@FunctionalInterface
public interface LevelConsumer {

    void onLevel(Side side, long price, int orderCount, long totalQuantity);
}
