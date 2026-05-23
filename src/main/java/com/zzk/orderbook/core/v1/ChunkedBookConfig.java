package com.zzk.orderbook.core.v1;

import com.zzk.orderbook.model.PrecisionSpec;

import java.util.Objects;

/**
 * Construction-time configuration for {@link ChunkedOrderBook}.
 *
 * <p>Use the static factories {@link #treeMap} / {@link #array} to avoid
 * passing meaningless fields for the unused directory kind:
 * {@code minChunkId}, {@code maxChunkId}, and {@code chunkPoolCapacity} are
 * only validated and consumed when {@code directoryKind == ARRAY}.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code precisionSpec} — per-instrument tick/step + scale; passed through
 *       to {@link L3OrderBook#precisionSpec()}. Validation against these
 *       values is the caller's responsibility (see {@link PrecisionUtils}).</li>
 *   <li>{@code askOriginTick} — lowest representable ask price (as a scaled
 *       long). Anchors the ask side's logical-index 0; prices below this map
 *       to a negative logical index and are rejected.</li>
 *   <li>{@code bidMaxTick} — highest representable bid price (scaled long).
 *       Anchors the bid side's logical-index 0 with {@code logicalIdx =
 *       bidMaxTick - priceTick}, so the inequality {@code askOriginTick <
 *       bidMaxTick} is required.</li>
 *   <li>{@code arenaCapacity} — fixed upper bound on concurrently-live orders
 *       across both sides. Sizes the primitive arrays inside {@link OrderArena}
 *       and the initial capacity of {@link OrderIdIndex}; running over this
 *       cap throws {@code "arena full"}.</li>
 *   <li>{@code directoryKind} — selects {@link TreeMapChunkDirectory} (sparse,
 *       grows on demand, O(log P) directory ops) or {@link ArrayChunkDirectory}
 *       (bounded, O(1) lookup, allocation-free hot path).</li>
 *   <li>{@code minChunkId} — lowest chunk id the array directory accepts.
 *       Below this throws on add. <em>ARRAY only.</em></li>
 *   <li>{@code maxChunkId} — highest chunk id the array directory accepts.
 *       Sets the size of the directory's backing array
 *       ({@code maxChunkId - minChunkId + 1}). <em>ARRAY only.</em></li>
 *   <li>{@code chunkPoolCapacity} — number of {@link PriceChunk} instances
 *       pre-allocated in the pool; must be {@code <= maxChunkId - minChunkId
 *       + 1}. Exhausting it throws {@code "chunk pool full"}. <em>ARRAY only.</em></li>
 * </ul>
 */
public record ChunkedBookConfig(
        PrecisionSpec precisionSpec,
        long askOriginTick,
        long bidMaxTick,
        int arenaCapacity,
        DirectoryKind directoryKind,
        int minChunkId,
        int maxChunkId,
        int chunkPoolCapacity
) {

    public ChunkedBookConfig {
        Objects.requireNonNull(precisionSpec, "precisionSpec");
        Objects.requireNonNull(directoryKind, "directoryKind");
        if (askOriginTick < 0) {
            throw new IllegalArgumentException("askOriginTick must be >= 0; got " + askOriginTick);
        }
        if (bidMaxTick <= 0) {
            throw new IllegalArgumentException("bidMaxTick must be > 0; got " + bidMaxTick);
        }
        if (askOriginTick >= bidMaxTick) {
            throw new IllegalArgumentException(
                "askOriginTick (" + askOriginTick + ") must be < bidMaxTick (" + bidMaxTick + ")");
        }
        if (arenaCapacity <= 0) {
            throw new IllegalArgumentException("arenaCapacity must be > 0; got " + arenaCapacity);
        }
        if (directoryKind == DirectoryKind.ARRAY) {
            if (minChunkId < 0) {
                throw new IllegalArgumentException("minChunkId must be >= 0; got " + minChunkId);
            }
            if (maxChunkId < minChunkId) {
                throw new IllegalArgumentException(
                    "maxChunkId (" + maxChunkId + ") must be >= minChunkId (" + minChunkId + ")");
            }
            int range = maxChunkId - minChunkId + 1;
            if (chunkPoolCapacity <= 0 || chunkPoolCapacity > range) {
                throw new IllegalArgumentException(
                    "chunkPoolCapacity must be in (0, " + range + "]; got " + chunkPoolCapacity);
            }
        }
    }

    public static ChunkedBookConfig treeMap(
            PrecisionSpec spec, long askOriginTick, long bidMaxTick, int arenaCapacity) {
        return new ChunkedBookConfig(
            spec, askOriginTick, bidMaxTick, arenaCapacity,
            DirectoryKind.TREEMAP, 0, 0, 0);
    }

    public static ChunkedBookConfig array(
            PrecisionSpec spec, long askOriginTick, long bidMaxTick, int arenaCapacity,
            int minChunkId, int maxChunkId, int chunkPoolCapacity) {
        return new ChunkedBookConfig(
            spec, askOriginTick, bidMaxTick, arenaCapacity,
            DirectoryKind.ARRAY, minChunkId, maxChunkId, chunkPoolCapacity);
    }
}
