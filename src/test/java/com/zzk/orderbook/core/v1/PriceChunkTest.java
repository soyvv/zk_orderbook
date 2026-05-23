package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceChunkTest {

    @Test
    void newChunkIsEmpty() {
        PriceChunk c = new PriceChunk(0L);
        assertEquals(0, c.nonEmptyCount);
        assertEquals(OrderArena.NULL, c.bestOffset);
        assertFalse(c.isLevelSet(0));
        assertFalse(c.isLevelSet(4095));
    }

    @Test
    void setLevelMarksBitAndTracksBest() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(100);
        assertTrue(c.isLevelSet(100));
        assertEquals(1, c.nonEmptyCount);
        assertEquals(100, c.bestOffset);

        c.setLevel(50);
        assertEquals(50, c.bestOffset, "best must update when a lower offset becomes non-empty");

        c.setLevel(200);
        assertEquals(50, c.bestOffset, "higher offset must not change best");
        assertEquals(3, c.nonEmptyCount);
    }

    @Test
    void setLevelIsIdempotent() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(100);
        c.setLevel(100);
        assertEquals(1, c.nonEmptyCount);
    }

    @Test
    void clearLevelRefreshesBest() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(50);
        c.setLevel(100);
        c.setLevel(4000);
        c.clearLevel(50);

        assertFalse(c.isLevelSet(50));
        assertEquals(100, c.bestOffset);

        c.clearLevel(100);
        assertEquals(4000, c.bestOffset);

        c.clearLevel(4000);
        assertEquals(0, c.nonEmptyCount);
        assertEquals(OrderArena.NULL, c.bestOffset);
    }

    @Test
    void clearLevelOnUnsetIsNoop() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(10);
        c.clearLevel(20);
        assertEquals(1, c.nonEmptyCount);
        assertEquals(10, c.bestOffset);
    }

    @Test
    void findBestOffsetCrossesWordBoundaries() {
        PriceChunk c = new PriceChunk(0L);
        // boundary cases: first offset (0), word-boundary (63→64), high offset (4095)
        c.setLevel(0);
        c.setLevel(63);
        c.setLevel(64);
        c.setLevel(4095);

        assertEquals(0, c.findBestOffsetFrom(0));
        assertEquals(63, c.findBestOffsetFrom(1));
        assertEquals(64, c.findBestOffsetFrom(64));
        assertEquals(4095, c.findBestOffsetFrom(65));
        assertEquals(OrderArena.NULL, c.findBestOffsetFrom(4096));
    }

    @Test
    void isCleanForReleaseHoldsForFreshChunk() {
        PriceChunk c = new PriceChunk(0L);
        assertTrue(c.isCleanForRelease());
    }

    @Test
    void isCleanForReleaseRejectsDirtyChunk() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(50);
        assertFalse(c.isCleanForRelease());
    }

    @Test
    void isCleanForReleaseRejectsBitmapInconsistency() {
        // nonEmptyCount==0 and bestOffset==NULL but a stale bit is set:
        // the bitmap walk catches it where the prior O(1) check would not.
        PriceChunk c = new PriceChunk(0L);
        c.levelBitmap[1] = 0x1L;
        assertFalse(c.isCleanForRelease());
    }

    @Test
    void clearActiveLevelsForReleaseClearsActiveLevelsOnly() {
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(5);
        c.setLevel(100);
        c.setLevel(4095);
        // Populate per-offset state to simulate live levels.
        c.totalQty[5] = 11L;
        c.orderCount[5] = 1;
        c.headOrder[5] = 7;
        c.tailOrder[5] = 7;
        c.totalQty[100] = 22L;
        c.orderCount[100] = 2;
        c.totalQty[4095] = 33L;

        c.clearActiveLevelsForRelease();

        // Observable contract:
        assertTrue(c.isCleanForRelease());
        assertFalse(c.isLevelSet(5));
        assertFalse(c.isLevelSet(100));
        assertFalse(c.isLevelSet(4095));
        // The cleared offsets should be zero/NULL.
        assertEquals(0L, c.totalQty[5]);
        assertEquals(0, c.orderCount[5]);
        assertEquals(OrderArena.NULL, c.headOrder[5]);
        assertEquals(OrderArena.NULL, c.tailOrder[5]);
    }

    @Test
    void isCleanForReleaseAfterNaturalEmpty() {
        // Mimic BookSide.removeOrder's natural-empty path: each level cleared
        // via clearLevel() after FIFO unlink zeroes per-offset state.
        PriceChunk c = new PriceChunk(0L);
        c.setLevel(50);
        c.totalQty[50] = 100L;
        c.orderCount[50] = 1;
        c.headOrder[50] = 7;
        c.tailOrder[50] = 7;

        // Simulate removeOrder's effect when the last order at the level goes:
        c.headOrder[50] = OrderArena.NULL;
        c.tailOrder[50] = OrderArena.NULL;
        c.orderCount[50] = 0;
        c.totalQty[50] = 0L;
        c.clearLevel(50);

        assertTrue(c.isCleanForRelease(),
            "natural-empty path should leave the chunk in clean state");
    }
}
