package com.zzk.orderbook.invariant;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.model.Side;

/**
 * Asserts book invariants from analysis.md §3. Call after every op in randomized tests.
 *
 * Checks (TODO):
 *  - every active order appears exactly once in level storage and once in order index
 *  - no empty price levels exist
 *  - bids strictly descending, asks strictly ascending
 *  - getByPrice and level-iteration agree on contents per price
 *  - getByLevel rank matches sorted iteration order
 *  - price % spec.priceTick == 0 and qty % spec.qtyStep == 0 for every active order
 */
public final class InvariantChecker {

    private InvariantChecker() {}

    public static void assertInvariants(L3OrderBook book) {
        assertSideOrdering(book, Side.BID);
        assertSideOrdering(book, Side.ASK);
        // TODO: implement remaining checks
    }

    private static void assertSideOrdering(L3OrderBook book, Side side) {
        // TODO: walk book.levels(side) and verify strict price ordering for the side
    }
}
