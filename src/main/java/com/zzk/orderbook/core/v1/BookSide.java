package com.zzk.orderbook.core.v1;

import com.zzk.orderbook.model.Side;

import static com.zzk.orderbook.core.v1.PriceChunk.CHUNK_BITS;
import static com.zzk.orderbook.core.v1.PriceChunk.CHUNK_MASK;

/**
 * Owns one side's chunk directory, best-price cache, and FIFO order links.
 *
 * <p>Logical index conventions: lower is always better.
 * <ul>
 *   <li>ASK: {@code logicalIdx = priceTick - askOriginTick}</li>
 *   <li>BID: {@code logicalIdx = bidMaxTick - priceTick}</li>
 * </ul>
 */
final class BookSide {

    static final long NO_CHUNK = Long.MIN_VALUE;

    final Side side;
    final byte sideTag;
    private final boolean reversed;
    private final long anchorTick;

    final ChunkDirectory directory;

    long bestChunkId = NO_CHUNK;
    int bestOffset = OrderArena.NULL;
    private int depthCount;

    BookSide(Side side, long anchorTick, boolean reversed, ChunkDirectory directory) {
        this.side = side;
        this.sideTag = (side == Side.BID) ? (byte) 'B' : (byte) 'A';
        this.anchorTick = anchorTick;
        this.reversed = reversed;
        this.directory = directory;
    }

    long logicalIdxOf(long priceTick) {
        return reversed ? (anchorTick - priceTick) : (priceTick - anchorTick);
    }

    long priceTickOf(long chunkId, int offset) {
        long logical = (chunkId << CHUNK_BITS) | offset;
        return reversed ? (anchorTick - logical) : (logical + anchorTick);
    }

    int depth() {
        return depthCount;
    }

    /** Clear cached best + depth; delegates state reset to the directory. */
    void reset() {
        directory.reset();
        bestChunkId = NO_CHUNK;
        bestOffset = OrderArena.NULL;
        depthCount = 0;
    }

    boolean hasBest() {
        return bestChunkId != NO_CHUNK;
    }

    long bestPriceTick() {
        if (!hasBest()) {
            throw new IllegalStateException("side empty");
        }
        return priceTickOf(bestChunkId, bestOffset);
    }

    /** Append a passive order at {@code priceTick}; returns the new arena slot. */
    int appendOrder(OrderArena arena, OrderIdIndex idIndex, long orderId, long priceTick, long qty) {
        long logical = logicalIdxOf(priceTick);
        if (logical < 0) {
            throw new IllegalArgumentException(
                    "price " + priceTick + " out of range for side " + side
                            + " (anchorTick=" + anchorTick + ")");
        }
        long chunkId = logical >>> CHUNK_BITS;
        int offset = (int) (logical & CHUNK_MASK);

        int slot = arena.allocate(orderId, sideTag, priceTick, qty, chunkId, offset);
        PriceChunk chunk = directory.getOrCreate(chunkId);

        if (!chunk.isLevelSet(offset)) {
            chunk.setLevel(offset);
            chunk.headOrder[offset] = slot;
            chunk.tailOrder[offset] = slot;
            chunk.totalQty[offset] = qty;
            chunk.orderCount[offset] = 1;
            depthCount++;
        } else {
            int tail = chunk.tailOrder[offset];
            arena.prevOrder[slot] = tail;
            arena.nextOrder[tail] = slot;
            chunk.tailOrder[offset] = slot;
            chunk.totalQty[offset] += qty;
            chunk.orderCount[offset]++;
        }

        idIndex.put(orderId, arena.handleOf(slot));
        maybeImproveBest(chunkId, chunk);
        return slot;
    }

    /** Unlink an order from its level FIFO. Updates aggregates, drops empty levels/chunks. */
    void removeOrder(OrderArena arena, OrderIdIndex idIndex, int slot) {
        long chunkId = arena.chunkId[slot];
        int offset = arena.offset[slot];
        long orderQty = arena.qty[slot];
        long orderId = arena.orderId[slot];

        PriceChunk chunk = directory.get(chunkId);
        int prev = arena.prevOrder[slot];
        int next = arena.nextOrder[slot];
        if (prev == OrderArena.NULL) {
            chunk.headOrder[offset] = next;
        } else {
            arena.nextOrder[prev] = next;
        }
        if (next == OrderArena.NULL) {
            chunk.tailOrder[offset] = prev;
        } else {
            arena.prevOrder[next] = prev;
        }

        chunk.orderCount[offset]--;
        chunk.totalQty[offset] -= orderQty;

        boolean wasBestLevel = (chunkId == bestChunkId && offset == bestOffset);
        boolean chunkDropped = false;
        if (chunk.orderCount[offset] == 0) {
            chunk.clearLevel(offset);
            depthCount--;
            if (chunk.nonEmptyCount == 0) {
                directory.remove(chunkId);
                chunkDropped = true;
            }
        }
        idIndex.remove(orderId);
        arena.free(slot);

        if (wasBestLevel) {
            refreshBest();
        } else if (chunkDropped && chunkId == bestChunkId) {
            // Best chunk gone but the cached best wasn't the cleared level — defensive refresh.
            refreshBest();
        }
    }

    void updateOrderQty(OrderArena arena, int slot, long newQty) {
        long chunkId = arena.chunkId[slot];
        int offset = arena.offset[slot];
        PriceChunk chunk = directory.get(chunkId);
        long delta = newQty - arena.qty[slot];
        arena.qty[slot] = newQty;
        chunk.totalQty[offset] += delta;
    }

    private void maybeImproveBest(long chunkId, PriceChunk chunk) {
        if (bestChunkId == NO_CHUNK || chunkId < bestChunkId) {
            bestChunkId = chunkId;
            bestOffset = chunk.bestOffset;
        } else if (chunkId == bestChunkId) {
            bestOffset = chunk.bestOffset;
        }
    }

    private void refreshBest() {
        PriceChunk first = directory.firstChunk();
        if (first == null) {
            bestChunkId = NO_CHUNK;
            bestOffset = OrderArena.NULL;
        } else {
            bestChunkId = first.chunkId;
            bestOffset = first.bestOffset;
        }
    }
}
