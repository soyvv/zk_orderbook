package com.zzk.orderbook.core.v1;

/**
 * Maps external {@code orderId} to a packed arena handle (slot + generation).
 *
 * <p>Implementations return {@link #missingValue()} on lookup miss to avoid
 * boxing. The default sentinel is {@link Long#MIN_VALUE}, which cannot collide
 * with any real arena handle (slot fits in 32 bits unsigned).
 */
interface OrderIdIndex {

    long MISSING = Long.MIN_VALUE;

    long get(long orderId);

    void put(long orderId, long handle);

    long remove(long orderId);

    boolean containsKey(long orderId);

    int size();

    /** Drop all entries; required for {@code reset()} on the order book. */
    void clear();
}
