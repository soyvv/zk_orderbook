package com.zzk.orderbook.model;

/**
 * Immutable level view for snapshot/query results. Aggregated, not per-order.
 */
public record BookSnapshotLevel(Side side, long price, int orderCount, long totalQuantity) {
}
