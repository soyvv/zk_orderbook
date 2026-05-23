package com.zzk.orderbook.core;

import com.zzk.orderbook.model.PrecisionSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Boundary helpers for callers converting between the engine's scaled
 * {@code long} representation and human / exchange decimal forms.
 *
 * <p>The order book itself only ever sees scaled {@code long} prices and
 * quantities — these utilities are <b>not</b> used on the hot path. They
 * exist so applications can:
 *
 * <ul>
 *   <li>parse exchange refdata (Binance {@code PRICE_FILTER.tickSize},
 *       OKX {@code tickSz}/{@code lotSz}) into a {@link PrecisionSpec}</li>
 *   <li>convert user-facing decimals to scaled longs before submitting
 *       orders, and back when rendering quotes</li>
 *   <li>pre-validate alignment / bounds at the application boundary</li>
 * </ul>
 *
 * <p>All conversions use {@link BigDecimal} — no floating-point on any
 * conversion path.
 */
public final class PrecisionUtils {

    private PrecisionUtils() {
    }

    // --- decimal <-> scaled long --------------------------------------------

    /**
     * Convert a decimal value to the engine's scaled-{@code long} form.
     * Throws {@link ArithmeticException} if {@code value} has more decimal
     * places than {@code scale} (no implicit rounding).
     */
    public static long toScaledLong(BigDecimal value, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0; got " + scale);
        }
        return value.setScale(scale, RoundingMode.UNNECESSARY)
            .movePointRight(scale)
            .longValueExact();
    }

    /** Parse {@code value} as a decimal then call {@link #toScaledLong(BigDecimal, int)}. */
    public static long toScaledLong(String value, int scale) {
        return toScaledLong(new BigDecimal(value), scale);
    }

    /** Inverse of {@link #toScaledLong}: scaled long back to a {@link BigDecimal}. */
    public static BigDecimal toBigDecimal(long scaledValue, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0; got " + scale);
        }
        return BigDecimal.valueOf(scaledValue, scale);
    }

    // --- refdata parsing ----------------------------------------------------

    /**
     * Number of decimal places carried by {@code numericString}. Preserves
     * trailing zeros (so {@code "0.10" → 2}, {@code "1.0" → 1}, {@code "1" → 0}).
     * Negative scales (scientific notation like {@code "1E2"}) are clamped
     * to 0.
     */
    public static int scaleOf(String numericString) {
        int s = new BigDecimal(numericString).scale();
        return Math.max(0, s);
    }

    /**
     * Build a {@link PrecisionSpec} from exchange tick/step strings — scale is
     * inferred from the decimal places in the input (not from sibling
     * precision integers like Binance's {@code pricePrecision}).
     *
     * <pre>{@code
     * parse("0.01", "0.00000001")
     *   → priceScale=2, priceTick=1, qtyScale=8, qtyStep=1
     *
     * parse("0.10", "0.001")
     *   → priceScale=2, priceTick=10, qtyScale=3, qtyStep=1
     * }</pre>
     */
    public static PrecisionSpec parse(String priceTick, String qtyStep) {
        int priceScale = scaleOf(priceTick);
        int qtyScale = scaleOf(qtyStep);
        return PrecisionSpec.of(
            priceScale, toScaledLong(priceTick, priceScale),
            qtyScale, toScaledLong(qtyStep, qtyScale));
    }

    /**
     * Full refdata form. Optional bounds are passed as nullable decimal strings;
     * each is converted at the appropriate scale. {@code minNotional} is treated
     * as a price-scale quote-currency value (callers can override by passing a
     * pre-scaled {@link PrecisionSpec} directly if their notional semantics
     * differ).
     */
    public static PrecisionSpec parse(
            String priceTick,
            String qtyStep,
            String minPrice,
            String maxPrice,
            String minQty,
            String maxQty,
            String minNotional,
            String contractMultiplier) {
        int priceScale = scaleOf(priceTick);
        int qtyScale = scaleOf(qtyStep);
        return new PrecisionSpec(
            priceScale, toScaledLong(priceTick, priceScale),
            qtyScale, toScaledLong(qtyStep, qtyScale),
            optionalScaled(minPrice, priceScale),
            optionalScaled(maxPrice, priceScale),
            optionalScaled(minQty, qtyScale),
            optionalScaled(maxQty, qtyScale),
            optionalScaled(minNotional, priceScale),
            optionalScaled(contractMultiplier, qtyScale));
    }

    private static Long optionalScaled(String value, int scale) {
        return (value == null || value.isBlank()) ? null : toScaledLong(value, scale);
    }

    // --- alignment ----------------------------------------------------------

    public static boolean isPriceAligned(long priceLong, PrecisionSpec spec) {
        return priceLong % spec.priceTick() == 0;
    }

    public static boolean isQtyAligned(long qtyLong, PrecisionSpec spec) {
        return qtyLong > 0 && qtyLong % spec.qtyStep() == 0;
    }

    /**
     * Throws {@link IllegalArgumentException} if {@code priceLong} is not a
     * multiple of {@code spec.priceTick()}.
     */
    public static void requirePriceAligned(long priceLong, PrecisionSpec spec) {
        if (priceLong % spec.priceTick() != 0) {
            throw new IllegalArgumentException(
                "price " + priceLong + " not aligned to priceTick " + spec.priceTick());
        }
    }

    /**
     * Throws {@link IllegalArgumentException} if {@code qtyLong} is not a
     * positive multiple of {@code spec.qtyStep()}. Callers must filter the
     * cancel-by-zero path ({@code qty == 0}) before invoking this.
     */
    public static void requireQtyAligned(long qtyLong, PrecisionSpec spec) {
        if (qtyLong <= 0) {
            throw new IllegalArgumentException("qty must be > 0; got " + qtyLong);
        }
        if (qtyLong % spec.qtyStep() != 0) {
            throw new IllegalArgumentException(
                "qty " + qtyLong + " not aligned to qtyStep " + spec.qtyStep());
        }
    }

    // --- bounds -------------------------------------------------------------

    /**
     * Apply optional refdata bounds (min/max price, min/max qty, min notional).
     * Any field that's {@code null} on {@code spec} is skipped. Notional is
     * computed as {@code priceLong * qtyLong / 10^qtyScale} so it ends up at
     * the same scale as {@code minNotional} (price-scale).
     */
    public static void requireBounds(long priceLong, long qtyLong, PrecisionSpec spec) {
        if (spec.minPrice() != null && priceLong < spec.minPrice()) {
            throw new IllegalArgumentException(
                "price " + priceLong + " below minPrice " + spec.minPrice());
        }
        if (spec.maxPrice() != null && priceLong > spec.maxPrice()) {
            throw new IllegalArgumentException(
                "price " + priceLong + " above maxPrice " + spec.maxPrice());
        }
        if (spec.minQty() != null && qtyLong < spec.minQty()) {
            throw new IllegalArgumentException(
                "qty " + qtyLong + " below minQty " + spec.minQty());
        }
        if (spec.maxQty() != null && qtyLong > spec.maxQty()) {
            throw new IllegalArgumentException(
                "qty " + qtyLong + " above maxQty " + spec.maxQty());
        }
        if (spec.minNotional() != null) {
            long notional = notionalAtPriceScale(priceLong, qtyLong, spec.qtyScale());
            if (notional < spec.minNotional()) {
                throw new IllegalArgumentException(
                    "notional " + notional + " below minNotional " + spec.minNotional());
            }
        }
    }

    /**
     * {@code priceLong * qtyLong / 10^qtyScale} — yields a value at price-scale.
     * Uses {@link Math#multiplyExact} so overflow throws rather than silently
     * wrapping.
     */
    public static long notionalAtPriceScale(long priceLong, long qtyLong, int qtyScale) {
        long product = Math.multiplyExact(priceLong, qtyLong);
        long divisor = pow10(qtyScale);
        return product / divisor;
    }

    private static long pow10(int n) {
        long r = 1L;
        for (int i = 0; i < n; i++) {
            r = Math.multiplyExact(r, 10L);
        }
        return r;
    }
}
