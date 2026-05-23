package com.zzk.orderbook.core.v1;

/**
 * Fixed-capacity recycling pool for {@link PriceChunk}. Pre-allocates all
 * chunks at construction so the order-book hot path never calls
 * {@code new PriceChunk(...)}.
 *
 * <p>Two release paths encode caller intent:
 * <ul>
 *   <li>{@link #releaseClean} — caller asserts the chunk is already clean
 *       (natural-empty path: {@code nonEmptyCount} dropped to 0 through
 *       {@code clearLevel()} calls inside {@code BookSide.removeOrder}). The
 *       pool only validates and pushes onto the free stack — no zeroing.</li>
 *   <li>{@link #releaseClearing} — caller hands back a chunk with possibly
 *       live state (forced-flush path: {@code ArrayChunkDirectory.reset}).
 *       The pool calls {@link PriceChunk#clearActiveLevelsForRelease} then
 *       validates.</li>
 * </ul>
 *
 * <p>The split keeps the hot-path acquire alloc-free *and* zeroing-free, and
 * removes the implicit contract where a single {@code release()} would
 * sometimes-clean depending on caller state.
 *
 * <p>Ownership/state guards still apply:
 * <ul>
 *   <li>{@code chunk.poolOwner} stamps the owning pool — foreign chunks are
 *       rejected on either release path</li>
 *   <li>{@code chunk.inPool} is true iff the chunk is on the free stack —
 *       double release / release-while-active throw</li>
 * </ul>
 *
 * <p>Single-threaded; not safe for concurrent access.
 */
final class ChunkPool {

    private final PriceChunk[] chunks;
    private final int[] freeStack;
    private int freeSize;

    ChunkPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0; got " + capacity);
        }
        this.chunks = new PriceChunk[capacity];
        this.freeStack = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            PriceChunk c = new PriceChunk(0L);
            c.poolSlot = i;
            c.poolOwner = this;
            c.inPool = true;
            chunks[i] = c;
            freeStack[i] = i;
        }
        this.freeSize = capacity;
    }

    int capacity() {
        return chunks.length;
    }

    int freeSize() {
        return freeSize;
    }

    PriceChunk acquire(long chunkId) {
        if (freeSize == 0) {
            throw new IllegalStateException("chunk pool full (capacity=" + chunks.length + ")");
        }
        int slot = freeStack[--freeSize];
        PriceChunk chunk = chunks[slot];
        if (chunk.poolOwner != this || !chunk.inPool) {
            throw new IllegalStateException("pool free-stack corruption at slot=" + slot);
        }
        chunk.inPool = false;
        chunk.resetForAcquire(chunkId);
        return chunk;
    }

    /**
     * Pool-return for chunks that the caller asserts are already clean. Used
     * by {@code ArrayChunkDirectory.remove} when {@code BookSide.removeOrder}
     * naturally drained the chunk to {@code nonEmptyCount == 0}. Throws if
     * the chunk fails {@link PriceChunk#isCleanForRelease}.
     */
    void releaseClean(PriceChunk chunk) {
        checkOwnership(chunk);
        if (!chunk.isCleanForRelease()) {
            throw new IllegalStateException(
                "releaseClean called on dirty chunk: nonEmptyCount="
                    + chunk.nonEmptyCount + " bestOffset=" + chunk.bestOffset);
        }
        pushFree(chunk);
    }

    /**
     * Pool-return for chunks that may have live state. Pool calls
     * {@link PriceChunk#clearActiveLevelsForRelease} to clean active offsets,
     * then validates. Used by {@code ArrayChunkDirectory.reset} at the
     * iteration boundary.
     */
    void releaseClearing(PriceChunk chunk) {
        checkOwnership(chunk);
        chunk.clearActiveLevelsForRelease();
        if (!chunk.isCleanForRelease()) {
            throw new IllegalStateException("clearActiveLevelsForRelease failed to clean chunk");
        }
        pushFree(chunk);
    }

    private void checkOwnership(PriceChunk chunk) {
        if (chunk.poolOwner != this) {
            throw new IllegalArgumentException("foreign chunk (not owned by this pool)");
        }
        if (chunk.inPool) {
            throw new IllegalStateException("double release of chunk poolSlot=" + chunk.poolSlot);
        }
    }

    private void pushFree(PriceChunk chunk) {
        chunk.inPool = true;
        freeStack[freeSize++] = chunk.poolSlot;
    }
}
