package com.zzk.orderbook.core.v1;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.model.BookSnapshotLevel;
import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.PriceLevel;
import com.zzk.orderbook.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedOrderBookTest {

    private static final PrecisionSpec SPEC = PrecisionSpec.of(0, 1L, 0, 1L);

    private ChunkedOrderBook book;

    @BeforeEach
    void setUp() {
        // Generous mid=1_000_000 range so the small1-style price band fits.
        book = new ChunkedOrderBook(ChunkedBookConfig.treeMap(
            SPEC,
            /* askOriginTick = */ 0L,
            /* bidMaxTick    = */ 2_000_000L,
            /* arenaCapacity = */ 1024));
    }

    @Test
    void addOrderOnEmptyBookCreatesLevel() {
        book.add(1, Side.BID, 10_000, 50);

        assertEquals(1, book.depth(Side.BID));
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(1, book.orderCount());

        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        assertNotNull(level);
        assertEquals(10_000L, level.price());
        assertEquals(Side.BID, level.side());
        assertEquals(1, level.orderCount());
        assertEquals(50L, level.totalQuantity());

        book.assertInternalInvariants();
    }

    @Test
    void updateChangesQuantityWithoutRelocation() {
        book.add(1, Side.BID, 10_000, 50);
        book.update(1, 80);

        Order o = book.getOrder(1);
        assertNotNull(o);
        assertEquals(80L, o.quantity());
        assertEquals(80L, book.getByPrice(Side.BID, 10_000).totalQuantity());
        assertEquals(1, book.depth(Side.BID));
        book.assertInternalInvariants();
    }

    @Test
    void updateWithZeroQuantityRemovesOrder() {
        book.add(1, Side.BID, 10_000, 50);
        book.add(2, Side.BID, 10_000, 30);

        book.update(1, 0);

        assertNull(book.getOrder(1));
        assertEquals(1, book.orderCount());
        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        assertNotNull(level);
        assertEquals(1, level.orderCount());
        assertEquals(30L, level.totalQuantity());
        book.assertInternalInvariants();
    }

    @Test
    void removingLastOrderAtPriceRemovesLevel() {
        book.add(1, Side.BID, 10_000, 50);
        book.remove(1);

        assertEquals(0, book.depth(Side.BID));
        assertEquals(0, book.orderCount());
        assertNull(book.getByPrice(Side.BID, 10_000));
        assertNull(book.getOrder(1));
        book.assertInternalInvariants();
    }

    @Test
    void fifoOrderPreservedWithinLevel() {
        book.add(1, Side.BID, 10_000, 10);
        book.add(2, Side.BID, 10_000, 20);
        book.add(3, Side.BID, 10_000, 30);

        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        Long[] ids = level.orders().stream().map(Order::orderId).toArray(Long[]::new);
        assertEquals(1L, ids[0]);
        assertEquals(2L, ids[1]);
        assertEquals(3L, ids[2]);
        assertEquals(60L, level.totalQuantity());
        book.assertInternalInvariants();
    }

    @Test
    void removeHeadOfFifoLeavesRestIntact() {
        book.add(1, Side.BID, 10_000, 10);
        book.add(2, Side.BID, 10_000, 20);
        book.add(3, Side.BID, 10_000, 30);

        book.remove(1);

        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        Long[] ids = level.orders().stream().map(Order::orderId).toArray(Long[]::new);
        assertEquals(2L, ids[0]);
        assertEquals(3L, ids[1]);
        assertEquals(50L, level.totalQuantity());
        book.assertInternalInvariants();
    }

    @Test
    void removeTailOfFifoLeavesRestIntact() {
        book.add(1, Side.BID, 10_000, 10);
        book.add(2, Side.BID, 10_000, 20);
        book.add(3, Side.BID, 10_000, 30);

        book.remove(3);

        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        Long[] ids = level.orders().stream().map(Order::orderId).toArray(Long[]::new);
        assertEquals(1L, ids[0]);
        assertEquals(2L, ids[1]);
        assertEquals(30L, level.totalQuantity());
        book.assertInternalInvariants();
    }

    @Test
    void removeMiddleOfFifoLeavesRestIntact() {
        book.add(1, Side.BID, 10_000, 10);
        book.add(2, Side.BID, 10_000, 20);
        book.add(3, Side.BID, 10_000, 30);

        book.remove(2);

        PriceLevel level = book.getByPrice(Side.BID, 10_000);
        Long[] ids = level.orders().stream().map(Order::orderId).toArray(Long[]::new);
        assertEquals(1L, ids[0]);
        assertEquals(3L, ids[1]);
        assertEquals(40L, level.totalQuantity());
        book.assertInternalInvariants();
    }

    @Test
    void bestPriceTracksAddsAndRemoves() {
        book.add(1, Side.BID, 9_900, 10);
        book.add(2, Side.BID, 10_100, 10);
        book.add(3, Side.BID, 10_000, 10);

        // best bid should be 10_100 (highest)
        assertEquals(10_100L, book.bidSideForTest().bestPriceTick());

        book.remove(2);
        assertEquals(10_000L, book.bidSideForTest().bestPriceTick());

        book.remove(3);
        assertEquals(9_900L, book.bidSideForTest().bestPriceTick());

        book.remove(1);
        assertEquals(0, book.depth(Side.BID));
        assertEquals(BookSide.NO_CHUNK, book.bidSideForTest().bestChunkId);

        book.assertInternalInvariants();
    }

    @Test
    void askBestIsLowestPrice() {
        book.add(1, Side.ASK, 10_300, 10);
        book.add(2, Side.ASK, 10_100, 10);
        book.add(3, Side.ASK, 10_200, 10);

        assertEquals(10_100L, book.askSideForTest().bestPriceTick());

        book.remove(2);
        assertEquals(10_200L, book.askSideForTest().bestPriceTick());
        book.assertInternalInvariants();
    }

    @Test
    void duplicateOrderIdThrows() {
        book.add(1, Side.BID, 10_000, 50);
        assertThrows(IllegalStateException.class,
                () -> book.add(1, Side.BID, 10_100, 50));
    }

    @Test
    void unknownOrderIdOnUpdateOrRemoveThrows() {
        assertThrows(IllegalArgumentException.class, () -> book.update(42, 10));
        assertThrows(IllegalArgumentException.class, () -> book.remove(42));
    }

    @Test
    void getByPriceReturnsNullForMissingLevel() {
        assertNull(book.getByPrice(Side.BID, 10_000));
        book.add(1, Side.BID, 10_000, 50);
        assertNotNull(book.getByPrice(Side.BID, 10_000));
        assertNull(book.getByPrice(Side.BID, 10_001));
        assertNull(book.getByPrice(Side.ASK, 10_000));
        book.assertInternalInvariants();
    }

    @Test
    void crossChunkBoundaryAdds() {
        // 4096 ticks per chunk; pick prices straddling a boundary
        long base = PriceChunk.CHUNK_SIZE; // 4096
        book.add(1, Side.ASK, base - 1, 10);    // logical 4095 → chunk 0
        book.add(2, Side.ASK, base,     20);    // logical 4096 → chunk 1
        book.add(3, Side.ASK, base + 1, 30);    // logical 4097 → chunk 1

        assertEquals(3, book.depth(Side.ASK));
        assertEquals(base - 1, book.askSideForTest().bestPriceTick());

        book.remove(1);
        assertEquals(base, book.askSideForTest().bestPriceTick());

        book.remove(2);
        assertEquals(base + 1, book.askSideForTest().bestPriceTick());
        book.assertInternalInvariants();
    }

    @Test
    void interfaceCompatibility() {
        L3OrderBook iface = book;
        assertEquals(SPEC, iface.precisionSpec());
        iface.add(1, Side.BID, 10_000, 50).isEmpty(); // returns empty list
        assertTrue(iface.add(2, Side.BID, 10_000, 30).isEmpty());
    }

    // --- M4 iteration / snapshot / getByLevel / trim -------------------------

    @Test
    void levelsIterateBidsHighToLow() {
        book.add(1, Side.BID, 9_900, 10);
        book.add(2, Side.BID, 10_000, 20);
        book.add(3, Side.BID, 9_800, 30);

        List<Long> prices = new ArrayList<>();
        for (PriceLevel pl : book.levels(Side.BID)) {
            prices.add(pl.price());
        }
        assertEquals(List.of(10_000L, 9_900L, 9_800L), prices);
        book.assertInternalInvariants();
    }

    @Test
    void levelsIterateAsksLowToHigh() {
        book.add(1, Side.ASK, 10_300, 10);
        book.add(2, Side.ASK, 10_100, 20);
        book.add(3, Side.ASK, 10_200, 30);

        List<Long> prices = new ArrayList<>();
        for (PriceLevel pl : book.levels(Side.ASK)) {
            prices.add(pl.price());
        }
        assertEquals(List.of(10_100L, 10_200L, 10_300L), prices);
    }

    @Test
    void levelsIterationCrossesChunkBoundary() {
        long base = PriceChunk.CHUNK_SIZE;
        book.add(1, Side.ASK, base - 1, 10);
        book.add(2, Side.ASK, base, 20);
        book.add(3, Side.ASK, base + 1, 30);
        book.add(4, Side.ASK, base + PriceChunk.CHUNK_SIZE, 40);  // chunk 2

        List<Long> prices = new ArrayList<>();
        for (PriceLevel pl : book.levels(Side.ASK)) {
            prices.add(pl.price());
        }
        assertEquals(List.of(base - 1, base, base + 1, base + PriceChunk.CHUNK_SIZE), prices);
    }

    @Test
    void snapshotEmitsAggregates() {
        book.add(1, Side.BID, 10_000, 50);
        book.add(2, Side.BID, 10_000, 30);
        book.add(3, Side.BID, 9_900, 20);

        List<BookSnapshotLevel> snap = new ArrayList<>();
        for (BookSnapshotLevel s : book.snapshot(Side.BID)) {
            snap.add(s);
        }
        assertEquals(2, snap.size());
        assertEquals(10_000L, snap.get(0).price());
        assertEquals(2, snap.get(0).orderCount());
        assertEquals(80L, snap.get(0).totalQuantity());
        assertEquals(9_900L, snap.get(1).price());
        assertEquals(1, snap.get(1).orderCount());
        assertEquals(20L, snap.get(1).totalQuantity());
    }

    @Test
    void getByLevelHonorsRanking() {
        book.add(1, Side.BID, 9_900, 10);
        book.add(2, Side.BID, 10_000, 10);
        book.add(3, Side.BID, 10_100, 10);

        assertEquals(10_100L, book.getByLevel(Side.BID, 0).price());
        assertEquals(10_000L, book.getByLevel(Side.BID, 1).price());
        assertEquals(9_900L, book.getByLevel(Side.BID, 2).price());
        assertNull(book.getByLevel(Side.BID, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> book.getByLevel(Side.BID, -1));
    }

    @Test
    void trimRemovesLevelsBeyondDepth() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_000 - i * 100, 10);
            book.add(200 + i, Side.ASK, 10_100 + i * 100, 10);
        }

        int removed = book.trim(2);

        assertEquals(6, removed);
        assertEquals(2, book.depth(Side.BID));
        assertEquals(2, book.depth(Side.ASK));
        assertEquals(4, book.orderCount());
        assertEquals(10_000L, book.getByLevel(Side.BID, 0).price());
        assertEquals(9_900L, book.getByLevel(Side.BID, 1).price());
        assertEquals(10_100L, book.getByLevel(Side.ASK, 0).price());
        assertEquals(10_200L, book.getByLevel(Side.ASK, 1).price());
        book.assertInternalInvariants();
    }

    @Test
    void trimIsIdempotent() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_000 - i * 100, 10);
        }
        book.trim(3);
        int second = book.trim(3);
        assertEquals(0, second);
        assertEquals(3, book.depth(Side.BID));
        book.assertInternalInvariants();
    }

    @Test
    void trimToZeroClearsBothSides() {
        book.add(1, Side.BID, 10_000, 10);
        book.add(2, Side.ASK, 10_100, 10);
        int removed = book.trim(0);
        assertEquals(2, removed);
        assertEquals(0, book.depth(Side.BID));
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(0, book.orderCount());
        book.assertInternalInvariants();
    }
}
