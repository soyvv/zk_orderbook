package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitmapUtilsTest {

    @Test
    void nextSetBitFindsBitsAcrossWordBoundaries() {
        long[] words = new long[2];
        words[0] = (1L << 0) | (1L << 63);
        words[1] = 1L << 1;

        assertEquals(0, BitmapUtils.nextSetBit(words, 0, 128));
        assertEquals(63, BitmapUtils.nextSetBit(words, 1, 128));
        assertEquals(65, BitmapUtils.nextSetBit(words, 64, 128));
        assertEquals(BitmapUtils.NOT_FOUND, BitmapUtils.nextSetBit(words, 66, 128));
    }

    @Test
    void nextSetBitRespectsBitLimit() {
        long[] words = new long[2];
        words[1] = 1L << 10;

        assertEquals(BitmapUtils.NOT_FOUND, BitmapUtils.nextSetBit(words, 0, 70));
        assertEquals(74, BitmapUtils.nextSetBit(words, 0, 75));
    }

    @Test
    void emptyAndClearAll() {
        long[] words = {0L, 4L};
        assertFalse(BitmapUtils.isEmpty(words));

        BitmapUtils.clearAll(words);

        assertTrue(BitmapUtils.isEmpty(words));
        assertEquals(BitmapUtils.NOT_FOUND, BitmapUtils.nextSetBit(words, 0, 128));
    }
}
