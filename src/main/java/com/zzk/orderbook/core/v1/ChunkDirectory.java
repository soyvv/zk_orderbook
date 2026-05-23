package com.zzk.orderbook.core.v1;

import java.util.Iterator;

/**
 * Ordered store of {@link PriceChunk}s keyed by chunkId, smallest first.
 *
 * <p>The order book iterates chunks best-to-worst (lowest chunkId first
 * after the {@link BookSide} logical-index transform), so all directory
 * implementations must support that traversal cheaply.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link TreeMapChunkDirectory} — default, ordered map; O(log P) per op</li>
 * </ul>
 * Future bounded-array directories may be added behind this interface
 * (see docs/low_latency_design_plan.md §7).
 */
interface ChunkDirectory {

    PriceChunk getOrCreate(long chunkId);

    PriceChunk get(long chunkId);

    void remove(long chunkId);

    /** Lowest-keyed chunk, or {@code null} if empty. */
    PriceChunk firstChunk();

    int size();

    boolean isEmpty();

    /** Iterate chunks best-to-worst (ascending chunkId). */
    Iterator<PriceChunk> iterator();

    /** Iterate chunks best-to-worst, starting from {@code fromChunkId} inclusive. */
    Iterator<PriceChunk> iteratorFrom(long fromChunkId);

    /**
     * Drop all chunks from the directory. Pool-backed impls must release
     * chunks back to their pool so {@code reset()} is allocation-free; map-backed
     * impls simply {@code clear()} the underlying map.
     */
    void reset();
}
