package com.zzk.orderbook.core.v1;

/** Selects the {@link ChunkDirectory} implementation used by {@link ChunkedOrderBook}. */
enum DirectoryKind {
    TREEMAP,
    ARRAY
}
