package com.zzk.orderbook.precision;

import java.math.BigDecimal;

/**
 * Boundary conversion between human/exchange decimal forms and the engine's
 * scaled {@code long} representation. No floating-point on the conversion path.
 */
public final class PriceQtyConverter {

    private PriceQtyConverter() {}

    public static long toScaledLong(BigDecimal value, int scale) {
        throw new UnsupportedOperationException("TODO: setScale(scale, UNNECESSARY) then movePointRight + longValueExact");
    }

    public static long toScaledLong(String value, int scale) {
        throw new UnsupportedOperationException("TODO: delegate to BigDecimal overload");
    }

    public static BigDecimal toBigDecimal(long scaledValue, int scale) {
        throw new UnsupportedOperationException("TODO: BigDecimal.valueOf(scaledValue, scale)");
    }
}
