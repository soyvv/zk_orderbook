package com.zzk.orderbook.core.ref;

import java.util.HashMap;

/** HashMap-backed reference {@link OrderIndex}; replace later with a primitive long-keyed map. */
public final class HashMapOrderIndex implements OrderIndex {

    private final HashMap<Long, OrderRef> map = new HashMap<>();

    @Override
    public OrderRef get(long orderId) {
        return map.get(orderId);
    }

    @Override
    public void put(long orderId, OrderRef ref) {
        map.put(orderId, ref);
    }

    @Override
    public OrderRef remove(long orderId) {
        return map.remove(orderId);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean contains(long orderId) {
        return map.containsKey(orderId);
    }

    @Override
    public void clear() {
        map.clear();
    }
}
