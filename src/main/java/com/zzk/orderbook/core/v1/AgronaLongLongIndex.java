package com.zzk.orderbook.core.v1;

import org.agrona.collections.Long2LongHashMap;

/**
 * {@link OrderIdIndex} backed by Agrona's primitive {@link Long2LongHashMap}.
 *
 * <p>Agrona returns {@code missingValue} on lookup miss; we configure it to
 * match {@link OrderIdIndex#MISSING} so callers see the same sentinel
 * regardless of the underlying map.
 */
final class AgronaLongLongIndex implements OrderIdIndex {

    /**
     * Global orderId → arena handle map. The handle packs
     * {@code (generation << 32) | slot} so a single primitive long carries
     * everything needed to locate the order in {@link OrderArena} and detect
     * stale references after slot reuse.
     */
    private final Long2LongHashMap map;

    AgronaLongLongIndex(int initialCapacity, float loadFactor) {
        // shouldAvoidAllocation=true caches the iterator/keySet/values views on the
        // instance so callers like InvariantHelper that touch them don't allocate
        // per access. Hot-path get/put/remove never allocate either way.
        this.map = new Long2LongHashMap(initialCapacity, loadFactor, MISSING, true);
    }

    @Override
    public long get(long orderId) {
        return map.get(orderId);
    }

    @Override
    public void put(long orderId, long handle) {
        map.put(orderId, handle);
    }

    @Override
    public long remove(long orderId) {
        return map.remove(orderId);
    }

    @Override
    public boolean containsKey(long orderId) {
        return map.containsKey(orderId);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
    }
}
