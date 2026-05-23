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
 * <p>{@code askOriginTick} anchors the ask side's logical-index 0 (lowest
 * representable ask), and {@code bidMaxTick} anchors the bid side's
 * logical-index 0 (highest representable bid).
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
