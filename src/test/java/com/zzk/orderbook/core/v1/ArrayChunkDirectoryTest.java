package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrayChunkDirectoryTest {

    private ArrayChunkDirectory dir(int min, int max, int poolCap) {
        return new ArrayChunkDirectory(min, max, new ChunkPool(poolCap));
    }

    @Test
    void emptyDirectoryHasNoFirstChunk() {
        ArrayChunkDirectory d = dir(0, 3, 4);
        assertTrue(d.isEmpty());
        assertEquals(0, d.size());
        assertNull(d.firstChunk());
    }

    @Test
    void boundaryChunkIdsWorkMinAndMax() {
        ArrayChunkDirectory d = dir(5, 10, 6);
        PriceChunk low = d.getOrCreate(5L);
        PriceChunk high = d.getOrCreate(10L);
        assertSame(low, d.get(5L));
        assertSame(high, d.get(10L));
        assertEquals(5L, low.chunkId);
        assertEquals(10L, high.chunkId);
    }

    @Test
    void outOfRangeChunkIdRejectedOnGetOrCreate() {
        ArrayChunkDirectory d = dir(5, 10, 2);
        assertThrows(IllegalArgumentException.class, () -> d.getOrCreate(4L));
        assertThrows(IllegalArgumentException.class, () -> d.getOrCreate(11L));
    }

    @Test
    void outOfRangeChunkIdReturnsNullOnGet() {
        ArrayChunkDirectory d = dir(5, 10, 2);
        assertNull(d.get(4L));
        assertNull(d.get(11L));
    }

    @Test
    void getOrCreateThenGetReturnsSameInstance() {
        ArrayChunkDirectory d = dir(0, 7, 4);
        PriceChunk first = d.getOrCreate(3L);
        PriceChunk same = d.getOrCreate(3L);
        assertSame(first, same);
        assertSame(first, d.get(3L));
        assertEquals(1, d.size());
    }

    @Test
    void firstChunkUpdatesOnInsertionBelowCurrentFirst() {
        ArrayChunkDirectory d = dir(0, 7, 4);
        d.getOrCreate(5L);
        assertEquals(5L, d.firstChunk().chunkId);
        d.getOrCreate(3L);
        assertEquals(3L, d.firstChunk().chunkId);
        d.getOrCreate(0L);
        assertEquals(0L, d.firstChunk().chunkId);
    }

    @Test
    void firstChunkRescansAfterRemovalOfCurrentFirst() {
        ArrayChunkDirectory d = dir(0, 7, 4);
        d.getOrCreate(1L);
        d.getOrCreate(3L);
        d.getOrCreate(6L);
        assertEquals(1L, d.firstChunk().chunkId);

        d.remove(1L);
        assertEquals(3L, d.firstChunk().chunkId);

        d.remove(3L);
        assertEquals(6L, d.firstChunk().chunkId);

        d.remove(6L);
        assertNull(d.firstChunk());
        assertTrue(d.isEmpty());
    }

    @Test
    void iteratorWalksBestToWorstSkippingInactive() {
        ArrayChunkDirectory d = dir(0, 15, 4);
        d.getOrCreate(2L);
        d.getOrCreate(5L);
        d.getOrCreate(11L);

        List<Long> ids = collectIds(d.iterator());
        assertEquals(List.of(2L, 5L, 11L), ids);
    }

    @Test
    void iteratorFromSkipsChunksBeforeStart() {
        ArrayChunkDirectory d = dir(0, 15, 4);
        d.getOrCreate(1L);
        d.getOrCreate(5L);
        d.getOrCreate(12L);

        assertEquals(List.of(5L, 12L), collectIds(d.iteratorFrom(5L)));
        assertEquals(List.of(12L), collectIds(d.iteratorFrom(10L)));

        // from <= min: walk all
        assertEquals(List.of(1L, 5L, 12L), collectIds(d.iteratorFrom(0L)));
        // from > max: empty
        assertFalse(d.iteratorFrom(99L).hasNext());
    }

    @Test
    void removeReleasesChunkBackToPoolForReuse() {
        ArrayChunkDirectory d = dir(0, 7, 2);
        PriceChunk c5 = d.getOrCreate(5L);
        d.remove(5L);

        PriceChunk c2 = d.getOrCreate(2L);
        // Pool capacity is only 2 — must be slot-reused.
        assertSame(c5, c2);
        assertEquals(2L, c2.chunkId);
    }

    @Test
    void crossWordBitmapBoundaries() {
        // Range 0..127 spans two bitmap words; verify firstChunk + iterator across the boundary.
        ArrayChunkDirectory d = dir(0, 127, 4);
        d.getOrCreate(63L);
        d.getOrCreate(64L);
        d.getOrCreate(127L);

        assertEquals(63L, d.firstChunk().chunkId);
        assertEquals(List.of(63L, 64L, 127L), collectIds(d.iterator()));

        d.remove(63L);
        assertEquals(64L, d.firstChunk().chunkId);
        d.remove(64L);
        assertEquals(127L, d.firstChunk().chunkId);
    }

    @Test
    void poolExhaustionBubblesUp() {
        ArrayChunkDirectory d = dir(0, 7, 2);
        d.getOrCreate(0L);
        d.getOrCreate(1L);
        assertThrows(IllegalStateException.class, () -> d.getOrCreate(2L));
    }

    @Test
    void getOrCreateAfterRemoveProducesFreshChunk() {
        ArrayChunkDirectory d = dir(0, 7, 4);
        PriceChunk first = d.getOrCreate(2L);
        // Mimic the natural-empty path: BookSide.removeOrder always clears
        // each level (clearLevel + per-offset state) before nonEmptyCount
        // hits 0 and the directory drops the chunk via releaseClean.
        first.setLevel(10);
        first.clearLevel(10);
        assertTrue(first.isCleanForRelease(),
            "directory.remove expects a clean chunk via releaseClean");

        d.remove(2L);
        PriceChunk second = d.getOrCreate(2L);
        // Same slot got reused; chunkId updated.
        assertSame(first, second);
        assertEquals(2L, second.chunkId);
        assertTrue(second.isCleanForRelease());
        assertFalse(second.isLevelSet(10));
    }

    @Test
    void resetReleasesLiveChunksViaClearingAndReacquireIsClean() {
        // Forced-flush path: directory has live chunks; reset() must call
        // releaseClearing so the pool cleans them before returning to the
        // free stack. Verify the reacquired chunk reports clean.
        ArrayChunkDirectory d = dir(0, 7, 4);
        PriceChunk live = d.getOrCreate(3L);
        live.setLevel(42);
        live.totalQty[42] = 500L;
        live.orderCount[42] = 5;
        live.headOrder[42] = 17;
        live.tailOrder[42] = 23;

        d.reset();
        assertTrue(d.isEmpty());

        PriceChunk reacquired = d.getOrCreate(3L);
        assertSame(live, reacquired);
        assertEquals(3L, reacquired.chunkId);
        assertTrue(reacquired.isCleanForRelease());
        assertFalse(reacquired.isLevelSet(42));
        // The previously-active offset was cleaned by releaseClearing.
        assertEquals(0L, reacquired.totalQty[42]);
        assertEquals(0, reacquired.orderCount[42]);
        assertEquals(OrderArena.NULL, reacquired.headOrder[42]);
        assertEquals(OrderArena.NULL, reacquired.tailOrder[42]);
    }

    @Test
    void removeRejectsLiveChunk() {
        // Sanity check that the natural-empty contract is enforced: if a
        // caller manages to call directory.remove on a still-live chunk
        // (because they forgot the natural-empty preconditions), the pool's
        // releaseClean throws loudly rather than silently corrupting state.
        ArrayChunkDirectory d = dir(0, 7, 4);
        PriceChunk c = d.getOrCreate(3L);
        c.setLevel(10);  // live state, never cleared

        assertThrows(IllegalStateException.class, () -> d.remove(3L));
    }

    @Test
    void invalidConfigsRejected() {
        ChunkPool p = new ChunkPool(2);
        assertThrows(IllegalArgumentException.class,
            () -> new ArrayChunkDirectory(-1, 5, p));
        assertThrows(IllegalArgumentException.class,
            () -> new ArrayChunkDirectory(10, 5, p));
        // pool larger than range
        assertThrows(IllegalArgumentException.class,
            () -> new ArrayChunkDirectory(0, 0, new ChunkPool(4)));
    }

    @Test
    void distinctChunkIdsGetDistinctInstances() {
        ArrayChunkDirectory d = dir(0, 7, 4);
        PriceChunk a = d.getOrCreate(0L);
        PriceChunk b = d.getOrCreate(1L);
        assertNotSame(a, b);
    }

    private static List<Long> collectIds(Iterator<PriceChunk> it) {
        List<Long> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next().chunkId);
        }
        return out;
    }
}
