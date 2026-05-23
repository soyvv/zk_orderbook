package com.zzk.orderbook.precision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class PrecisionParserTest {

    @Test
    void parsesTickAndStepStringsIntoScaledLongs() {
        fail("TODO: parse(\"0.01\", \"0.00000001\") -> priceScale=2, priceTick=1, qtyScale=8, qtyStep=1");
    }

    @Test
    void inferScaleFromTrailingZeros() {
        fail("TODO: parse(\"0.10\", \"0.001\") -> priceScale=2, priceTick=10, qtyScale=3, qtyStep=1");
    }

    @Test
    void roundTripScaledLongAndBigDecimal() {
        fail("TODO: toBigDecimal(toScaledLong(\"123.45\", 2), 2).equals(new BigDecimal(\"123.45\"))");
    }

    @Test
    void requirePriceAlignedRejectsMisaligned() {
        fail("TODO: priceTick=1 (=0.01), priceLong=10005 (=100.05) passes; 10001 with tick 10 (=0.10) fails");
    }

    @Test
    void requireQtyAlignedRejectsMisaligned() {
        fail("TODO: qtyStep=1 (=0.00000001), aligned qty passes; misaligned throws");
    }

    @Test
    void requireQtyAlignedRejectsNonPositiveQty() {
        fail("TODO: qty=0 must be filtered by caller; validator rejects qty<=0");
    }

    @Test
    void requireBoundsAppliesMinMaxAndNotional() {
        fail("TODO: spec with minQty/maxQty/minNotional; assert each bound triggers rejection");
    }

    @Test
    void requireBoundsNoOpWhenAllNull() {
        fail("TODO: PrecisionSpec.of(...) (no bounds) accepts any aligned values");
    }
}
