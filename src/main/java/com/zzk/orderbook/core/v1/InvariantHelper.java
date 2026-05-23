package com.zzk.orderbook.core.v1;

import java.util.Iterator;

/**
 * Internal {@link ChunkedOrderBook} invariants from
 * docs/low_latency_design_plan.md §9. Tests call this via
 * {@link ChunkedOrderBook#assertInternalInvariants()} at strategic points.
 */
final class InvariantHelper {

    private InvariantHelper() {}

    static void check(ChunkedOrderBook book) {
        checkSide(book.bidSideForTest(), book.arenaForTest());
        checkSide(book.askSideForTest(), book.arenaForTest());

        int liveFromArena = book.arenaForTest().liveCount();
        int liveFromIndex = book.idIndexForTest().size();
        if (liveFromArena != liveFromIndex) {
            throw new IllegalStateException(
                    "arena.liveCount=" + liveFromArena + " idIndex.size=" + liveFromIndex);
        }
    }

    private static void checkSide(BookSide bs, OrderArena arena) {
        int expectedDepth = 0;
        long expectedBestChunkId = BookSide.NO_CHUNK;
        int expectedBestOffset = OrderArena.NULL;

        Iterator<PriceChunk> it = bs.directory.iterator();
        boolean firstChunk = true;
        while (it.hasNext()) {
            PriceChunk chunk = it.next();
            int bitCount = 0;
            int chunkLowestSet = -1;
            for (int o = 0; o < PriceChunk.CHUNK_SIZE; o++) {
                boolean set = chunk.isLevelSet(o);
                int oc = chunk.orderCount[o];
                long tq = chunk.totalQty[o];
                int head = chunk.headOrder[o];
                int tail = chunk.tailOrder[o];
                if (set) {
                    bitCount++;
                    if (chunkLowestSet < 0) chunkLowestSet = o;
                    if (oc <= 0 || tq <= 0) {
                        throw new IllegalStateException(
                                "level set but empty aggregates at chunk=" + chunk.chunkId
                                        + " offset=" + o + " orderCount=" + oc + " totalQty=" + tq);
                    }
                    if (head == OrderArena.NULL || tail == OrderArena.NULL) {
                        throw new IllegalStateException(
                                "level set but null FIFO head/tail at chunk=" + chunk.chunkId
                                        + " offset=" + o);
                    }
                    checkFifo(arena, chunk, o, bs.sideTag, oc, tq);
                } else {
                    if (oc != 0 || tq != 0 || head != OrderArena.NULL || tail != OrderArena.NULL) {
                        throw new IllegalStateException(
                                "level cleared but dirty state at chunk=" + chunk.chunkId
                                        + " offset=" + o);
                    }
                }
            }
            if (bitCount != chunk.nonEmptyCount) {
                throw new IllegalStateException(
                        "chunk " + chunk.chunkId + " bitmap=" + bitCount
                                + " nonEmptyCount=" + chunk.nonEmptyCount);
            }
            if (chunk.nonEmptyCount > 0 && chunk.bestOffset != chunkLowestSet) {
                throw new IllegalStateException(
                        "chunk " + chunk.chunkId + " bestOffset=" + chunk.bestOffset
                                + " expected=" + chunkLowestSet);
            }
            expectedDepth += chunk.nonEmptyCount;
            if (firstChunk && chunk.nonEmptyCount > 0) {
                expectedBestChunkId = chunk.chunkId;
                expectedBestOffset = chunk.bestOffset;
                firstChunk = false;
            }
        }

        if (bs.depth() != expectedDepth) {
            throw new IllegalStateException(
                    bs.side + " depth=" + bs.depth() + " expected=" + expectedDepth);
        }
        if (bs.bestChunkId != expectedBestChunkId || bs.bestOffset != expectedBestOffset) {
            throw new IllegalStateException(
                    bs.side + " best cache=(" + bs.bestChunkId + "," + bs.bestOffset
                            + ") expected=(" + expectedBestChunkId + "," + expectedBestOffset + ")");
        }
    }

    private static void checkFifo(OrderArena arena, PriceChunk chunk, int offset,
                                  byte expectedSideTag, int expectedCount, long expectedTotalQty) {
        int slot = chunk.headOrder[offset];
        int prev = OrderArena.NULL;
        int count = 0;
        long totalQty = 0L;
        while (slot != OrderArena.NULL) {
            if (arena.prevOrder[slot] != prev) {
                throw new IllegalStateException(
                        "FIFO prev link broken at slot=" + slot + " expected prev=" + prev
                                + " actual=" + arena.prevOrder[slot]);
            }
            if (arena.side[slot] != expectedSideTag) {
                throw new IllegalStateException(
                        "FIFO order side mismatch at slot=" + slot + " expected="
                                + (char) expectedSideTag + " actual=" + (char) arena.side[slot]);
            }
            if (arena.chunkId[slot] != chunk.chunkId || arena.offset[slot] != offset) {
                throw new IllegalStateException(
                        "FIFO order chunk/offset mismatch at slot=" + slot);
            }
            count++;
            totalQty += arena.qty[slot];
            prev = slot;
            slot = arena.nextOrder[slot];
        }
        if (chunk.tailOrder[offset] != prev) {
            throw new IllegalStateException(
                    "FIFO tail mismatch at chunk=" + chunk.chunkId + " offset=" + offset
                            + " stored tail=" + chunk.tailOrder[offset] + " walked tail=" + prev);
        }
        if (count != expectedCount) {
            throw new IllegalStateException(
                    "FIFO count walked=" + count + " expected=" + expectedCount);
        }
        if (totalQty != expectedTotalQty) {
            throw new IllegalStateException(
                    "FIFO totalQty walked=" + totalQty + " expected=" + expectedTotalQty);
        }
    }
}
