package com.zzk.orderbook.core.ref;

/**
 * Global orderId -> OrderRef index for O(1) lookup on update/remove.
 * Wraps a HashMap; replaceable with a primitive long-keyed map later.
 */
public interface OrderIndex {

    OrderRef get(long orderId);

    void put(long orderId, OrderRef ref);

    OrderRef remove(long orderId);

    int size();

    boolean contains(long orderId);

    void clear();
}
