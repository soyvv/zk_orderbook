package com.zzk.orderbook.core.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderArenaTest {

    @Test
    void allocateAssignsSequentialSlotsAndStoresState() {
        OrderArena a = new OrderArena(3);
        int s0 = a.allocate(100L, (byte) 'B', 999L, 50L, 7L, 11);
        int s1 = a.allocate(101L, (byte) 'A', 1000L, 60L, 8L, 12);

        assertEquals(0, s0);
        assertEquals(1, s1);
        assertEquals(2, a.liveCount());
        assertEquals(100L, a.orderId[s0]);
        assertEquals(50L, a.qty[s0]);
        assertEquals(7L, a.chunkId[s0]);
        assertEquals(11, a.offset[s0]);
        assertEquals((byte) 'B', a.side[s0]);
    }

    @Test
    void freeReturnsSlotToFreeList() {
        OrderArena a = new OrderArena(2);
        int s0 = a.allocate(100L, (byte) 'B', 999L, 50L, 0L, 0);
        a.free(s0);

        assertEquals(0, a.liveCount());
        int s2 = a.allocate(102L, (byte) 'B', 1000L, 70L, 1L, 1);
        assertEquals(s0, s2);
        assertEquals(102L, a.orderId[s2]);
    }

    @Test
    void handleGenerationInvalidatesAfterFree() {
        OrderArena a = new OrderArena(2);
        int s = a.allocate(100L, (byte) 'B', 999L, 50L, 0L, 0);
        long staleHandle = a.handleOf(s);
        assertTrue(a.isLive(staleHandle));

        a.free(s);
        assertFalse(a.isLive(staleHandle));

        int s2 = a.allocate(101L, (byte) 'B', 1000L, 60L, 0L, 1);
        assertEquals(s, s2);
        long newHandle = a.handleOf(s2);
        assertTrue(a.isLive(newHandle));
        assertFalse(a.isLive(staleHandle), "old handle must remain stale after slot reuse");
        assertNotEquals(staleHandle, newHandle);
    }

    @Test
    void allocateThrowsWhenFull() {
        OrderArena a = new OrderArena(1);
        a.allocate(1L, (byte) 'B', 999L, 1L, 0L, 0);
        assertThrows(IllegalStateException.class,
                () -> a.allocate(2L, (byte) 'B', 999L, 1L, 0L, 0));
    }

    @Test
    void handlePackUnpackRoundTrip() {
        OrderArena a = new OrderArena(4);
        int s = a.allocate(42L, (byte) 'A', 999L, 1L, 0L, 0);
        long h = a.handleOf(s);
        assertEquals(s, OrderArena.slotOf(h));
        assertEquals(0, OrderArena.generationOf(h));
    }
}
