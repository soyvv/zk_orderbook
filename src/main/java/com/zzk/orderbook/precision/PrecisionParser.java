package com.zzk.orderbook.precision;

import com.zzk.orderbook.model.PrecisionSpec;

/**
 * Parses exchange refdata strings (e.g. Binance {@code PRICE_FILTER.tickSize},
 * OKX {@code tickSz} / {@code lotSz}) into a {@link PrecisionSpec}.
 *
 * Scale is inferred from the decimal places in the input string, not from
 * sibling precision integers (per analysis.md §5).
 */
public final class PrecisionParser {

    private PrecisionParser() {}

    /** Minimal form: price tick and qty step strings only, no optional bounds. */
    public static PrecisionSpec parse(String priceTick, String qtyStep) {
        throw new UnsupportedOperationException("TODO: infer scales, convert tick/step to scaled longs");
    }

    /** Full form including optional refdata bounds and contract multiplier. */
    public static PrecisionSpec parse(
            String priceTick,
            String qtyStep,
            String minPrice,
            String maxPrice,
            String minQty,
            String maxQty,
            String minNotional,
            String contractMultiplier
    ) {
        throw new UnsupportedOperationException("TODO: parse full refdata bundle");
    }
}
