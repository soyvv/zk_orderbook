package com.zzk.orderbook.model;

/**
 * Per-instrument precision and refdata, parsed from exchange tick/step fields.
 *
 * Core engine uses scaled {@code long} values; conversion happens at the boundary.
 *
 * <p>Required fields:
 * <ul>
 *   <li>{@code priceScale} — decimal places of the tick size (e.g. "0.01" → 2)</li>
 *   <li>{@code priceTick}  — tick size as scaled long (e.g. "0.01" with scale 2 → 1)</li>
 *   <li>{@code qtyScale}   — decimal places of the step/lot size</li>
 *   <li>{@code qtyStep}    — step/lot size as scaled long</li>
 * </ul>
 *
 * <p>Optional fields are nullable. Drive conversion and validation from tick/step,
 * not from precision integers (per analysis.md §5).
 */
public record PrecisionSpec(
        int priceScale,
        long priceTick,
        int qtyScale,
        long qtyStep,
        Long minPrice,
        Long maxPrice,
        Long minQty,
        Long maxQty,
        Long minNotional,
        Long contractMultiplier
) {

    public static PrecisionSpec of(int priceScale, long priceTick, int qtyScale, long qtyStep) {
        return new PrecisionSpec(priceScale, priceTick, qtyScale, qtyStep,
                null, null, null, null, null, null);
    }
}
