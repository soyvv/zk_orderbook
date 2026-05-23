package com.zzk.orderbook.core.v1;

import java.util.Iterator;
import java.util.TreeMap;

/** Default {@link ChunkDirectory}: {@link TreeMap}-backed, O(log P) per op. */
final class TreeMapChunkDirectory implements ChunkDirectory {

    private final TreeMap<Long, PriceChunk> chunks = new TreeMap<>();

    @Override
    public PriceChunk getOrCreate(long chunkId) {
        PriceChunk c = chunks.get(chunkId);
        if (c == null) {
            c = new PriceChunk(chunkId);
            chunks.put(chunkId, c);
        }
        return c;
    }

    @Override
    public PriceChunk get(long chunkId) {
        return chunks.get(chunkId);
    }

    @Override
    public void remove(long chunkId) {
        chunks.remove(chunkId);
    }

    @Override
    public PriceChunk firstChunk() {
        var e = chunks.firstEntry();
        return e == null ? null : e.getValue();
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    @Override
    public Iterator<PriceChunk> iterator() {
        return chunks.values().iterator();
    }

    @Override
    public Iterator<PriceChunk> iteratorFrom(long fromChunkId) {
        return chunks.tailMap(fromChunkId, true).values().iterator();
    }

    @Override
    public void reset() {
        // Drops every chunk reference. Next getOrCreate will allocate a fresh
        // PriceChunk — TreeMapChunkDirectory does not pool chunks.
        chunks.clear();
    }
}
