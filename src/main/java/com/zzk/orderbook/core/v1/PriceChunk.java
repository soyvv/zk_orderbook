package com.zzk.orderbook.core.v1;

import java.util.Arrays;

/**
 * Dense 4096-level block of one side's ladder. Each offset is one price tick;
 * non-empty levels are tracked in a 64-word bitmap so best-offset lookup is
 * {@code Long.numberOfTrailingZeros} per word.
 *
 * <p>{@code chunkId} is mutable so {@link ChunkPool} can recycle the chunk
 * for a different chunk id without re-allocating the per-level arrays. The
 * {@code poolSlot}/{@code poolOwner}/{@code inPool} fields are pool-bookkeeping
 * stamped at pool construction and toggled on acquire/release; unpooled
 * chunks (e.g. those constructed by {@code TreeMapChunkDirectory} or tests)
 * leave them at their defaults.
 */
final class PriceChunk {

    static final int CHUNK_BITS = 12;
    static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    static final int CHUNK_MASK = CHUNK_SIZE - 1;
    static final int BITMAP_WORDS = CHUNK_SIZE / Long.SIZE;

    /**
     * Logical chunk id this chunk currently represents. Mutable because
     * {@link ChunkPool} recycles instances: a chunk's id is overwritten by
     * {@link #resetForAcquire} when the pool hands it out again.
     */
    long chunkId;
    /** Sum of resting quantity per offset (price level). Maintained incrementally on add/remove/update. */
    final long[] totalQty = new long[CHUNK_SIZE];
    /** Number of resting orders per offset. {@code 0} ↔ level is empty (and bitmap bit is clear). */
    final int[] orderCount = new int[CHUNK_SIZE];
    /** Head (oldest) arena slot of the FIFO queue at each offset, or {@link OrderArena#NULL}. */
    final int[] headOrder = new int[CHUNK_SIZE];
    /** Tail (newest) arena slot of the FIFO queue at each offset, or {@link OrderArena#NULL}. */
    final int[] tailOrder = new int[CHUNK_SIZE];
    /**
     * Bitmap of which offsets are currently non-empty: bit {@code o} in word
     * {@code o >>> 6} is set iff {@code orderCount[o] > 0}. Lets best-offset
     * lookup walk 64-long words with {@link Long#numberOfTrailingZeros}
     * instead of scanning the full 4096-slot {@link #orderCount}.
     */
    final long[] levelBitmap = new long[BITMAP_WORDS];

    /**
     * Number of set bits in {@link #levelBitmap}. Tracked separately so
     * {@link ArrayChunkDirectory} can cheaply decide "drop the chunk" when it
     * drops to zero, without re-counting the bitmap.
     */
    int nonEmptyCount;
    /**
     * Lowest set offset in {@link #levelBitmap}, cached so the side's
     * best-price lookup avoids the bitmap scan on the common case where the
     * best level hasn't changed. {@link OrderArena#NULL} when chunk is empty.
     */
    int bestOffset = OrderArena.NULL;

    // Pool bookkeeping — mutated only by ChunkPool.
    /**
     * Index of this chunk in its owning {@link ChunkPool#chunks} array.
     * Stamped at pool construction, immutable afterwards. {@code -1} for
     * unpooled chunks (e.g. those created by {@link TreeMapChunkDirectory}).
     */
    int poolSlot = -1;
    /**
     * Reference to the owning pool, or {@code null} if unpooled. Used by
     * {@link ChunkPool#releaseClean}/{@link ChunkPool#releaseClearing} to
     * reject foreign chunks.
     */
    ChunkPool poolOwner;
    /**
     * {@code true} while the chunk is on the pool's free stack, {@code false}
     * while it's live in a directory. The pool toggles this to detect
     * double-release and release-while-active.
     */
    boolean inPool;

    PriceChunk(long chunkId) {
        this.chunkId = chunkId;
        Arrays.fill(headOrder, OrderArena.NULL);
        Arrays.fill(tailOrder, OrderArena.NULL);
    }

    /**
     * Stamp a fresh chunkId on a chunk that is already in clean state. Called
     * by {@link ChunkPool#acquire} on the hot path; the cleanliness pre-
     * condition is enforced by {@link ChunkPool#releaseClean} /
     * {@link ChunkPool#releaseClearing} at the release sites.
     */
    void resetForAcquire(long chunkId) {
        this.chunkId = chunkId;
        // Defensive re-assert: already 0 / NULL by contract.
        this.nonEmptyCount = 0;
        this.bestOffset = OrderArena.NULL;
    }

    /**
     * Bitmap-driven clear of active levels only. Used by
     * {@link ChunkPool#releaseClearing} when a forced-flush release path may
     * hand back a chunk with live state (e.g. {@code book.reset()} at
     * iteration boundaries). Stale data in inactive offsets is intentionally
     * left untouched — future {@link #setLevel} overwrites the offset before
     * it becomes observable.
     */
    void clearActiveLevelsForRelease() {
        for (int offset = BitmapUtils.nextSetBit(levelBitmap, 0, CHUNK_SIZE);
             offset != BitmapUtils.NOT_FOUND;
             offset = BitmapUtils.nextSetBit(levelBitmap, offset + 1, CHUNK_SIZE)) {
            totalQty[offset] = 0L;
            orderCount[offset] = 0;
            headOrder[offset] = OrderArena.NULL;
            tailOrder[offset] = OrderArena.NULL;
        }
        BitmapUtils.clearAll(levelBitmap);
        nonEmptyCount = 0;
        bestOffset = OrderArena.NULL;
    }

    /**
     * Strong invariant: cached counters agree with the bitmap. Walks the 64
     * bitmap words — cheap relative to {@link Arrays#fill} on 4096 slots —
     * and catches drift where {@code nonEmptyCount == 0} but a stray bit
     * remains set.
     */
    boolean isCleanForRelease() {
        if (nonEmptyCount != 0 || bestOffset != OrderArena.NULL) {
            return false;
        }
        return BitmapUtils.isEmpty(levelBitmap);
    }

    boolean isLevelSet(int offset) {
        return (levelBitmap[offset >>> 6] & (1L << (offset & 63))) != 0L;
    }

    void setLevel(int offset) {
        long mask = 1L << (offset & 63);
        int word = offset >>> 6;
        if ((levelBitmap[word] & mask) == 0L) {
            levelBitmap[word] |= mask;
            nonEmptyCount++;
            if (bestOffset == OrderArena.NULL || offset < bestOffset) {
                bestOffset = offset;
            }
        }
    }

    void clearLevel(int offset) {
        long mask = 1L << (offset & 63);
        int word = offset >>> 6;
        if ((levelBitmap[word] & mask) != 0L) {
            levelBitmap[word] &= ~mask;
            nonEmptyCount--;
            if (offset == bestOffset) {
                bestOffset = findBestOffsetFrom(offset + 1);
            }
        }
    }

    /** Lowest set offset >= start; returns {@link OrderArena#NULL} if none. */
    int findBestOffsetFrom(int start) {
        if (start >= CHUNK_SIZE) {
            return OrderArena.NULL;
        }
        int offset = BitmapUtils.nextSetBit(levelBitmap, start, CHUNK_SIZE);
        return offset == BitmapUtils.NOT_FOUND ? OrderArena.NULL : offset;
    }
}
