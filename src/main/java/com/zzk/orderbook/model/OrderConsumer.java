package com.zzk.orderbook.model;

/**
 * Visitor receiving each resting order during a per-level FIFO walk via
 * {@code L3OrderBook.forEachOrderAtPrice(side, price, consumer)}. Emits
 * primitives so the call path doesn't allocate.
 *
 * <p>Same caveat as {@link LevelConsumer}: store the consumer once.
 */
@FunctionalInterface
public interface OrderConsumer {

    void onOrder(long orderId, Side side, long price, long quantity);
}
