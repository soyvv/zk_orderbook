package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPoolTest {

    @Test
    void acquireReturnsCleanChunkWithStampedChunkId() {
        ChunkPool pool = new ChunkPool(2);
        PriceChunk a = pool.acquire(42L);
        assertEquals(42L, a.chunkId);
        assertTrue(a.isCleanForRelease());
        assertFalse(a.inPool);
        assertEquals(2, pool.capacity());
        assertEquals(1, pool.freeSize());
    }

    @Test
    void releaseCleanThenReacquireRetrievesSameInstance() {
        ChunkPool pool = new ChunkPool(2);
        PriceChunk first = pool.acquire(1L);
        pool.releaseClean(first);
        PriceChunk reacquired = pool.acquire(2L);
        assertSame(first, reacquired);
        assertEquals(2L, reacquired.chunkId);
    }

    @Test
    void releaseCleanRejectsDirtyChunk() {
        ChunkPool pool = new ChunkPool(1);
        PriceChunk c = pool.acquire(7L);
        c.setLevel(100);  // nonEmptyCount=1, bestOffset=100

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> pool.releaseClean(c));
        assertTrue(ex.getMessage().contains("dirty chunk"), ex.getMessage());
    }

    @Test
    void releaseCleanRejectsBitmapInconsistency() {
        // Simulate the failure mode the old O(1) check would have missed:
        // nonEmptyCount and bestOffset are at reset values but a bitmap bit is set.
        ChunkPool pool = new ChunkPool(1);
        PriceChunk c = pool.acquire(7L);
        c.levelBitmap[1] = 0x1L;  // bit set; nonEmptyCount untouched (still 0)
        // bestOffset still NULL — only the bitmap walk catches this drift.
        assertThrows(IllegalStateException.class, () -> pool.releaseClean(c));
    }

    @Test
    void releaseClearingCleansActiveLevelsAndPools() {
        ChunkPool pool = new ChunkPool(1);
        PriceChunk c = pool.acquire(7L);
        c.setLevel(50);
        c.totalQty[50] = 999L;
        c.orderCount[50] = 3;
        c.headOrder[50] = 11;
        c.tailOrder[50] = 13;

        pool.releaseClearing(c);  // must succeed and clean the chunk

        // After release, the chunk is in the pool; acquire a fresh one and
        // verify the observable contract (clean by construction).
        PriceChunk r = pool.acquire(99L);
        assertSame(c, r);  // slot reuse
        assertEquals(99L, r.chunkId);
        assertTrue(r.isCleanForRelease(),
            "reacquired chunk should report clean: " + describe(r));
        assertFalse(r.isLevelSet(50));
        // The previously-active offset should be zero/NULL after clearing.
        assertEquals(0L, r.totalQty[50]);
        assertEquals(0, r.orderCount[50]);
        assertEquals(OrderArena.NULL, r.headOrder[50]);
        assertEquals(OrderArena.NULL, r.tailOrder[50]);
    }

    @Test
    void exhaustionThrows() {
        ChunkPool pool = new ChunkPool(2);
        pool.acquire(1L);
        pool.acquire(2L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> pool.acquire(3L));
        assertTrue(ex.getMessage().contains("chunk pool full"));
    }

    @Test
    void doubleReleaseThrows() {
        ChunkPool pool = new ChunkPool(2);
        PriceChunk c = pool.acquire(1L);
        pool.releaseClean(c);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> pool.releaseClean(c));
        assertTrue(ex.getMessage().contains("double release"));
    }

    @Test
    void foreignChunkRejected() {
        ChunkPool a = new ChunkPool(1);
        ChunkPool b = new ChunkPool(1);
        PriceChunk fromA = a.acquire(1L);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> b.releaseClean(fromA));
        assertTrue(ex.getMessage().contains("foreign"));
    }

    @Test
    void unpooledChunkRejected() {
        ChunkPool pool = new ChunkPool(1);
        PriceChunk loose = new PriceChunk(0L);  // not owned by any pool
        assertThrows(IllegalArgumentException.class, () -> pool.releaseClean(loose));
        assertThrows(IllegalArgumentException.class, () -> pool.releaseClearing(loose));
    }

    @Test
    void capacityAndFreeSizeTrackState() {
        ChunkPool pool = new ChunkPool(3);
        assertEquals(3, pool.capacity());
        assertEquals(3, pool.freeSize());
        PriceChunk a = pool.acquire(1L);
        PriceChunk b = pool.acquire(2L);
        assertEquals(1, pool.freeSize());
        pool.releaseClean(a);
        assertEquals(2, pool.freeSize());
        pool.releaseClean(b);
        assertEquals(3, pool.freeSize());
    }

    @Test
    void distinctAcquiresReturnDistinctInstances() {
        ChunkPool pool = new ChunkPool(3);
        PriceChunk a = pool.acquire(1L);
        PriceChunk b = pool.acquire(2L);
        PriceChunk c = pool.acquire(3L);
        assertNotSame(a, b);
        assertNotSame(a, c);
        assertNotSame(b, c);
    }

    @Test
    void acquireReleaseCycleStaysHealthy() {
        // Smoke test that many acquire/releaseClean cycles don't corrupt the pool.
        ChunkPool pool = new ChunkPool(4);
        for (int i = 0; i < 1000; i++) {
            PriceChunk c = pool.acquire(i);
            assertEquals(i, c.chunkId);
            assertTrue(c.isCleanForRelease());
            pool.releaseClean(c);
        }
        assertEquals(4, pool.freeSize());
    }

    private static String describe(PriceChunk c) {
        return "nonEmptyCount=" + c.nonEmptyCount + " bestOffset=" + c.bestOffset;
    }
}
