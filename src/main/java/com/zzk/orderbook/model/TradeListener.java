package com.zzk.orderbook.model;

/**
 * Receiver for trade events emitted by an order book during matching.
 *
 * <p>Trade fields are passed as primitives so hot-path callers can avoid
 * allocating a {@link Trade} record per fill. Store the listener once
 * (long-lived field or static singleton) — passing a fresh capturing
 * lambda on every call would defeat the optimization.
 */
@FunctionalInterface
public interface TradeListener {

    void onTrade(long makerOrderId, long takerOrderId, Side takerSide, long price, long quantity);

    /** Listener that throws away every trade — useful in perf measurement. */
    TradeListener DISCARD = (m, t, s, p, q) -> { };
}
