package com.zzk.orderbook.model;

/**
 * Immutable trade record emitted when an incoming aggressive order crosses
 * resting liquidity. {@code price} is the maker's (passive) price — trades
 * always clear at the passive side, per standard exchange semantics.
 */
public record Trade(long makerOrderId, long takerOrderId, Side takerSide, long price, long quantity) {
}
