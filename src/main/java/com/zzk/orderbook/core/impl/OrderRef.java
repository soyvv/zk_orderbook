package com.zzk.orderbook.core.impl;

import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.Side;

/**
 * Back-pointer used by the global order index so update/remove can locate the
 * level container without re-walking the price tree.
 *
 * Immutable: current update semantics do not relocate orders across prices,
 * so {@code price} never changes once recorded.
 */
public record OrderRef(Order order, Side side, long price) {
}
