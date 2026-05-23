package com.zzk.orderbook.precision;

import com.zzk.orderbook.model.PrecisionSpec;

/**
 * Boundary validators. Callers invoke these before passing prices/quantities to
 * the order book (see analysis.md §5 — validation at the boundary, not inside
 * the hot path of the book core).
 */
public final class PrecisionValidator {

    private PrecisionValidator() {}

    /** Throws if {@code priceLong} is not a multiple of {@code spec.priceTick()}. */
    public static void requirePriceAligned(long priceLong, PrecisionSpec spec) {
        throw new UnsupportedOperationException("TODO: priceLong % spec.priceTick() == 0");
    }

    /**
     * Throws if {@code qtyLong} is not a multiple of {@code spec.qtyStep()}.
     * Caller must skip this check for the cancel path where {@code qty == 0}.
     */
    public static void requireQtyAligned(long qtyLong, PrecisionSpec spec) {
        throw new UnsupportedOperationException("TODO: qtyLong > 0 && qtyLong % spec.qtyStep() == 0");
    }

    /** Optional refdata bounds: min/max price, min/max qty, min notional. */
    public static void requireBounds(long priceLong, long qtyLong, PrecisionSpec spec) {
        throw new UnsupportedOperationException("TODO: apply nullable bounds + notional = price*qty");
    }
}
