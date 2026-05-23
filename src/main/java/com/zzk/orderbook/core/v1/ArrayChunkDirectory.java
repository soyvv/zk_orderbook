package com.zzk.orderbook.core.v1;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Bounded-array {@link ChunkDirectory} with O(1) chunk lookup and a long-word
 * bitmap for best-chunk discovery. Chunks are recycled through a
 * {@link ChunkPool} so the hot path never allocates.
 *
 * <p>Out-of-range chunk ids are rejected with {@link IllegalArgumentException};
 * pool exhaustion bubbles up as {@link IllegalStateException} from the pool.
 *
 * <p>{@code firstActiveIndex} caches the lowest active array index. It is
 * updated proactively on {@link #getOrCreate} (improves), invalidated to
 * {@code -1} on {@link #remove} of the cached entry, and rescanned lazily
 * by {@link #firstChunk}.
 */
final class ArrayChunkDirectory implements ChunkDirectory {

    private static final int FIRST_INVALID = -1;

    private final int minChunkId;
    private final int maxChunkId;
    private final PriceChunk[] chunksByIndex;
    private final long[] activeChunkBitmap;
    private final ChunkPool chunkPool;

    private int activeCount;
    private int firstActiveIndex = FIRST_INVALID;

    ArrayChunkDirectory(int minChunkId, int maxChunkId, ChunkPool chunkPool) {
        if (minChunkId < 0) {
            throw new IllegalArgumentException("minChunkId must be >= 0; got " + minChunkId);
        }
        if (maxChunkId < minChunkId) {
            throw new IllegalArgumentException(
                "maxChunkId (" + maxChunkId + ") must be >= minChunkId (" + minChunkId + ")");
        }
        int range = maxChunkId - minChunkId + 1;
        if (chunkPool.capacity() > range) {
            throw new IllegalArgumentException(
                "chunkPool capacity " + chunkPool.capacity()
                    + " exceeds chunk range " + range);
        }
        this.minChunkId = minChunkId;
        this.maxChunkId = maxChunkId;
        this.chunksByIndex = new PriceChunk[range];
        this.activeChunkBitmap = new long[(range + 63) >>> 6];
        this.chunkPool = chunkPool;
    }

    @Override
    public PriceChunk getOrCreate(long chunkId) {
        int idx = indexOf(chunkId, true);
        PriceChunk chunk = chunksByIndex[idx];
        if (chunk != null) {
            return chunk;
        }
        chunk = chunkPool.acquire(chunkId);
        chunksByIndex[idx] = chunk;
        setBit(idx);
        activeCount++;
        if (firstActiveIndex == FIRST_INVALID || idx < firstActiveIndex) {
            firstActiveIndex = idx;
        }
        return chunk;
    }

    @Override
    public PriceChunk get(long chunkId) {
        if (chunkId < minChunkId || chunkId > maxChunkId) {
            return null;
        }
        return chunksByIndex[(int) (chunkId - minChunkId)];
    }

    @Override
    public void remove(long chunkId) {
        int idx = indexOf(chunkId, true);
        PriceChunk chunk = chunksByIndex[idx];
        if (chunk == null) {
            return;
        }
        chunksByIndex[idx] = null;
        clearBit(idx);
        activeCount--;
        if (idx == firstActiveIndex) {
            firstActiveIndex = FIRST_INVALID;
        }
        // Natural-empty path: BookSide.removeOrder maintains the invariant
        // that the chunk is clean before it drops to nonEmptyCount==0.
        chunkPool.releaseClean(chunk);
    }

    @Override
    public PriceChunk firstChunk() {
        if (activeCount == 0) {
            return null;
        }
        int idx = firstActiveIndex;
        if (idx != FIRST_INVALID && chunksByIndex[idx] != null) {
            return chunksByIndex[idx];
        }
        idx = scanFirstSet(0);
        firstActiveIndex = idx;
        return idx == FIRST_INVALID ? null : chunksByIndex[idx];
    }

    @Override
    public int size() {
        return activeCount;
    }

    @Override
    public boolean isEmpty() {
        return activeCount == 0;
    }

    @Override
    public Iterator<PriceChunk> iterator() {
        return new BitmapIterator(0);
    }

    @Override
    public void reset() {
        // Forced-flush path: chunks may have live state. releaseClearing
        // calls clearActiveLevelsForRelease before pooling.
        for (int idx = BitmapUtils.nextSetBit(activeChunkBitmap, 0, chunksByIndex.length);
             idx != BitmapUtils.NOT_FOUND;
             idx = BitmapUtils.nextSetBit(activeChunkBitmap, idx + 1, chunksByIndex.length)) {
            chunkPool.releaseClearing(chunksByIndex[idx]);
            chunksByIndex[idx] = null;
        }
        BitmapUtils.clearAll(activeChunkBitmap);
        activeCount = 0;
        firstActiveIndex = FIRST_INVALID;
    }

    @Override
    public Iterator<PriceChunk> iteratorFrom(long fromChunkId) {
        int startIdx;
        if (fromChunkId <= minChunkId) {
            startIdx = 0;
        } else if (fromChunkId > maxChunkId) {
            return new BitmapIterator(chunksByIndex.length);  // empty
        } else {
            startIdx = (int) (fromChunkId - minChunkId);
        }
        return new BitmapIterator(startIdx);
    }

    // --- internals ----------------------------------------------------------

    private int indexOf(long chunkId, boolean throwOnMiss) {
        if (chunkId < minChunkId || chunkId > maxChunkId) {
            if (throwOnMiss) {
                throw new IllegalArgumentException(
                    "chunkId " + chunkId + " out of range [" + minChunkId
                        + ", " + maxChunkId + "]");
            }
            return -1;
        }
        return (int) (chunkId - minChunkId);
    }

    private void setBit(int idx) {
        activeChunkBitmap[idx >>> 6] |= (1L << (idx & 63));
    }

    private void clearBit(int idx) {
        activeChunkBitmap[idx >>> 6] &= ~(1L << (idx & 63));
    }

    /** Lowest set bit at or after {@code startIdx}, or {@link #FIRST_INVALID}. */
    private int scanFirstSet(int startIdx) {
        return BitmapUtils.nextSetBit(activeChunkBitmap, startIdx, chunksByIndex.length);
    }

    private final class BitmapIterator implements Iterator<PriceChunk> {

        private int nextIdx;

        BitmapIterator(int startIdx) {
            this.nextIdx = scanFirstSet(startIdx);
        }

        @Override
        public boolean hasNext() {
            return nextIdx != FIRST_INVALID;
        }

        @Override
        public PriceChunk next() {
            if (nextIdx == FIRST_INVALID) {
                throw new NoSuchElementException();
            }
            PriceChunk chunk = chunksByIndex[nextIdx];
            nextIdx = scanFirstSet(nextIdx + 1);
            return chunk;
        }
    }
}
