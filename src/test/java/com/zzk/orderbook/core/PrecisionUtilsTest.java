package com.zzk.orderbook.core;

import com.zzk.orderbook.model.PrecisionSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecisionUtilsTest {

    // --- conversion ---------------------------------------------------------

    @Test
    void toScaledLongFromDecimalAtScale() {
        assertEquals(10005L, PrecisionUtils.toScaledLong(new BigDecimal("100.05"), 2));
        assertEquals(50_000_000L, PrecisionUtils.toScaledLong(new BigDecimal("0.5"), 8));
        assertEquals(1L, PrecisionUtils.toScaledLong(new BigDecimal("0.01"), 2));
        assertEquals(0L, PrecisionUtils.toScaledLong(new BigDecimal("0"), 8));
    }

    @Test
    void toScaledLongFromString() {
        assertEquals(12345L, PrecisionUtils.toScaledLong("123.45", 2));
        assertEquals(100L, PrecisionUtils.toScaledLong("0.10", 3));
    }

    @Test
    void toScaledLongRejectsTooManyDecimalPlaces() {
        // 100.123 has 3 decimal places; scale=2 → rounding would be needed → throw.
        assertThrows(ArithmeticException.class,
            () -> PrecisionUtils.toScaledLong("100.123", 2));
    }

    @Test
    void toBigDecimalIsExactInverse() {
        assertEquals(new BigDecimal("100.05"),
            PrecisionUtils.toBigDecimal(10005L, 2));
        assertEquals(new BigDecimal("0.00000001"),
            PrecisionUtils.toBigDecimal(1L, 8));
    }

    @Test
    void roundTripScaledLongAndBigDecimal() {
        BigDecimal original = new BigDecimal("123.45");
        long scaled = PrecisionUtils.toScaledLong(original, 2);
        assertEquals(original, PrecisionUtils.toBigDecimal(scaled, 2));
    }

    @Test
    void negativeScaleRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.toScaledLong(new BigDecimal("1.0"), -1));
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.toBigDecimal(1L, -1));
    }

    // --- scaleOf ------------------------------------------------------------

    @Test
    void scaleOfPreservesTrailingZeros() {
        assertEquals(2, PrecisionUtils.scaleOf("0.10"));
        assertEquals(2, PrecisionUtils.scaleOf("0.01"));
        assertEquals(8, PrecisionUtils.scaleOf("0.00000001"));
        assertEquals(3, PrecisionUtils.scaleOf("0.001"));
    }

    @Test
    void scaleOfZeroForIntegers() {
        assertEquals(0, PrecisionUtils.scaleOf("1"));
        assertEquals(0, PrecisionUtils.scaleOf("100"));
    }

    // --- parse --------------------------------------------------------------

    @Test
    void parseBinanceBtcUsdt() {
        // Binance Spot BTCUSDT: tickSize=0.01, stepSize=0.00000001
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.00000001");
        assertEquals(2, spec.priceScale());
        assertEquals(1L, spec.priceTick());
        assertEquals(8, spec.qtyScale());
        assertEquals(1L, spec.qtyStep());
    }

    @Test
    void parseFuturesStyle() {
        // Binance USD-M Futures BTCUSDT: tickSize=0.10, stepSize=0.001
        PrecisionSpec spec = PrecisionUtils.parse("0.10", "0.001");
        assertEquals(2, spec.priceScale());
        assertEquals(10L, spec.priceTick());
        assertEquals(3, spec.qtyScale());
        assertEquals(1L, spec.qtyStep());
    }

    @Test
    void parseWithBounds() {
        PrecisionSpec spec = PrecisionUtils.parse(
            "0.01", "0.00000001",
            "1.00", "1000000.00",
            "0.00010000", "10000.00000000",
            "10.00",
            null);
        assertEquals(100L, spec.minPrice());
        assertEquals(100_000_000L, spec.maxPrice());
        assertEquals(10_000L, spec.minQty());
        assertEquals(1_000_000_000_000L, spec.maxQty());
        assertEquals(1000L, spec.minNotional());  // 10.00 at priceScale=2
        assertEquals(null, spec.contractMultiplier());
    }

    // --- alignment ----------------------------------------------------------

    @Test
    void isPriceAligned() {
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.00000001");
        assertTrue(PrecisionUtils.isPriceAligned(10005L, spec));   // 100.05
        assertTrue(PrecisionUtils.isPriceAligned(0L, spec));
        assertTrue(PrecisionUtils.isPriceAligned(-100L, spec));
    }

    @Test
    void isPriceAlignedRejectsMisalignedWithLargerTick() {
        // tickSize=0.10 → priceTick=10. priceLong=10001 (=100.01) is misaligned.
        PrecisionSpec spec = PrecisionUtils.parse("0.10", "0.001");
        assertFalse(PrecisionUtils.isPriceAligned(10001L, spec));
        assertTrue(PrecisionUtils.isPriceAligned(10000L, spec));
    }

    @Test
    void isQtyAlignedRejectsNonPositive() {
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.001");
        assertFalse(PrecisionUtils.isQtyAligned(0L, spec));
        assertFalse(PrecisionUtils.isQtyAligned(-1L, spec));
    }

    @Test
    void requirePriceAlignedRejectsMisaligned() {
        PrecisionSpec spec = PrecisionUtils.parse("0.10", "0.001");
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requirePriceAligned(10001L, spec));
        // Aligned should not throw:
        PrecisionUtils.requirePriceAligned(10000L, spec);
    }

    @Test
    void requireQtyAlignedRejectsMisaligned() {
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.001");
        // qtyStep=1 (=0.001). Any positive value is aligned (% 1 == 0).
        // Force a bigger step:
        PrecisionSpec coarse = PrecisionUtils.parse("0.01", "0.010");  // qtyStep=10
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireQtyAligned(15L, coarse));
        PrecisionUtils.requireQtyAligned(20L, coarse);  // 0.020 OK
    }

    @Test
    void requireQtyAlignedRejectsNonPositiveQty() {
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.001");
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireQtyAligned(0L, spec));
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireQtyAligned(-5L, spec));
    }

    // --- bounds -------------------------------------------------------------

    @Test
    void requireBoundsNoOpWhenAllNull() {
        // Bare spec has all bounds null; any aligned value should be accepted.
        PrecisionSpec spec = PrecisionUtils.parse("0.01", "0.00000001");
        PrecisionUtils.requireBounds(10005L, 50_000_000L, spec);
        PrecisionUtils.requireBounds(0L, 0L, spec);
        PrecisionUtils.requireBounds(Long.MAX_VALUE / 2, 1L, spec);
    }

    @Test
    void requireBoundsRejectsMinPrice() {
        PrecisionSpec spec = PrecisionUtils.parse(
            "0.01", "0.00000001",
            "1.00", null, null, null, null, null);
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireBounds(99L, 1L, spec));  // 0.99 < 1.00
        PrecisionUtils.requireBounds(100L, 1L, spec);
    }

    @Test
    void requireBoundsRejectsMaxQty() {
        PrecisionSpec spec = PrecisionUtils.parse(
            "0.01", "0.001",
            null, null, null, "1.000", null, null);
        // maxQty=1.000 → qtyLong cap = 1000
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireBounds(100L, 1001L, spec));
        PrecisionUtils.requireBounds(100L, 1000L, spec);
    }

    @Test
    void requireBoundsRejectsMinNotional() {
        // priceScale=2, qtyScale=3. minNotional=10.00 → 1000 at priceScale.
        // notional = priceLong * qtyLong / 10^qtyScale.
        //   price=100.00 (10000) × qty=0.050 (50) / 10^3 = 500_000 / 1000 = 500 (=5.00)
        // 500 < 1000 → reject.
        PrecisionSpec spec = PrecisionUtils.parse(
            "0.01", "0.001",
            null, null, null, null, "10.00", null);
        assertThrows(IllegalArgumentException.class,
            () -> PrecisionUtils.requireBounds(10000L, 50L, spec));
        // notional = 100.00 × 0.100 = 10.00 exactly → accepted.
        PrecisionUtils.requireBounds(10000L, 100L, spec);
    }

    @Test
    void notionalAtPriceScale() {
        // price=100.00 (10000 at scale 2) × qty=0.5 (5 at scale 1) / 10^1 = 50_000 / 10 = 5000 = 50.00
        assertEquals(5000L, PrecisionUtils.notionalAtPriceScale(10000L, 5L, 1));
        // price=100.05 (10005) × qty=1 (1 at scale 0) / 10^0 = 10005 = 100.05
        assertEquals(10005L, PrecisionUtils.notionalAtPriceScale(10005L, 1L, 0));
    }
}
