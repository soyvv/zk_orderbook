package com.zzk.orderbook.model;

/**
 * Caller-owned holder populated by {@code L3OrderBook.getOrder(orderId, out)}.
 * Public mutable fields by design — this is the allocation-free counterpart
 * to the immutable {@link Order} returned by the convenience API.
 *
 * <p>Callers should construct one instance and reuse it across many lookups.
 * Always check the {@code boolean} return value before reading fields;
 * after a miss the previous values may be stale (use {@link #clear()} to
 * reset between calls if that's preferable).
 */
public final class MutableOrder {

    public long orderId;
    public Side side;
    public long price;
    public long quantity;

    public MutableOrder() {
    }

    public void clear() {
        orderId = 0L;
        side = null;
        price = 0L;
        quantity = 0L;
    }
}
