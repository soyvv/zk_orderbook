package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDirectoryTest {

    @Test
    void emptyDirectoryHasNoFirstChunk() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        assertTrue(d.isEmpty());
        assertNull(d.firstChunk());
        assertEquals(0, d.size());
    }

    @Test
    void getOrCreateReusesExistingChunk() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        PriceChunk a = d.getOrCreate(5L);
        PriceChunk b = d.getOrCreate(5L);
        assertSame(a, b);
        assertEquals(1, d.size());
    }

    @Test
    void firstChunkReturnsLowestChunkId() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        d.getOrCreate(7L);
        d.getOrCreate(2L);
        d.getOrCreate(11L);
        assertNotNull(d.firstChunk());
        assertEquals(2L, d.firstChunk().chunkId);
    }

    @Test
    void removeDropsChunk() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        d.getOrCreate(2L);
        d.getOrCreate(3L);
        d.remove(2L);
        assertEquals(1, d.size());
        assertEquals(3L, d.firstChunk().chunkId);
    }

    @Test
    void iteratorWalksBestToWorst() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        d.getOrCreate(10L);
        d.getOrCreate(5L);
        d.getOrCreate(20L);

        Iterator<PriceChunk> it = d.iterator();
        assertEquals(5L, it.next().chunkId);
        assertEquals(10L, it.next().chunkId);
        assertEquals(20L, it.next().chunkId);
        assertFalse(it.hasNext());
    }

    @Test
    void iteratorFromSkipsChunksBeforeStart() {
        ChunkDirectory d = new TreeMapChunkDirectory();
        d.getOrCreate(5L);
        d.getOrCreate(10L);
        d.getOrCreate(20L);

        Iterator<PriceChunk> it = d.iteratorFrom(10L);
        assertEquals(10L, it.next().chunkId);
        assertEquals(20L, it.next().chunkId);
        assertFalse(it.hasNext());
    }
}
