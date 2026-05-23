package com.zzk.orderbook.core.v1;

import com.zzk.orderbook.model.LevelConsumer;
import com.zzk.orderbook.model.MutableLevel;
import com.zzk.orderbook.model.MutableOrder;
import com.zzk.orderbook.model.OrderConsumer;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the holder + visitor query APIs on {@link ChunkedOrderBook}.
 * Verifies observable contract — not implementation details.
 */
class ChunkedOrderBookHotApiTest {

    private static final PrecisionSpec SPEC = PrecisionSpec.of(0, 1L, 0, 1L);

    private ChunkedOrderBook book;

    @BeforeEach
    void setUp() {
        book = new ChunkedOrderBook(ChunkedBookConfig.treeMap(
            SPEC, 0L, 2_000_000L, 1024));
    }

    // --- getOrder(orderId, MutableOrder) -------------------------------------

    @Test
    void getOrderHolderHit() {
        book.add(7L, Side.BID, 10_000L, 50L);
        MutableOrder out = new MutableOrder();
        assertTrue(book.getOrder(7L, out));
        assertEquals(7L, out.orderId);
        assertEquals(Side.BID, out.side);
        assertEquals(10_000L, out.price);
        assertEquals(50L, out.quantity);
    }

    @Test
    void getOrderHolderMiss() {
        MutableOrder out = new MutableOrder();
        assertFalse(book.getOrder(99L, out));
    }

    @Test
    void getOrderHolderOverwritesOnSecondCall() {
        book.add(1L, Side.BID, 10_000L, 50L);
        book.add(2L, Side.ASK, 10_500L, 80L);
        MutableOrder out = new MutableOrder();

        assertTrue(book.getOrder(1L, out));
        assertEquals(Side.BID, out.side);
        assertEquals(10_000L, out.price);

        // Reuse the same holder: fields must reflect the new lookup.
        assertTrue(book.getOrder(2L, out));
        assertEquals(2L, out.orderId);
        assertEquals(Side.ASK, out.side);
        assertEquals(10_500L, out.price);
        assertEquals(80L, out.quantity);
    }

    // --- getByPrice(Side, long, MutableLevel) --------------------------------

    @Test
    void getByPriceHolderAggregatesQty() {
        book.add(1L, Side.BID, 10_000L, 30L);
        book.add(2L, Side.BID, 10_000L, 70L);
        book.add(3L, Side.BID, 10_000L, 100L);

        MutableLevel level = new MutableLevel();
        assertTrue(book.getByPrice(Side.BID, 10_000L, level));
        assertEquals(Side.BID, level.side);
        assertEquals(10_000L, level.price);
        assertEquals(3, level.orderCount);
        assertEquals(200L, level.totalQuantity);
    }

    @Test
    void getByPriceHolderMissingLevel() {
        MutableLevel level = new MutableLevel();
        assertFalse(book.getByPrice(Side.BID, 10_000L, level));
    }

    @Test
    void getByPriceHolderMissingSide() {
        book.add(1L, Side.BID, 10_000L, 50L);
        MutableLevel level = new MutableLevel();
        assertFalse(book.getByPrice(Side.ASK, 10_000L, level));
    }

    // --- getByLevel(Side, int, MutableLevel) ---------------------------------

    @Test
    void getByLevelHolderHonorsRanking() {
        book.add(1L, Side.BID, 9_900L, 10L);
        book.add(2L, Side.BID, 10_000L, 20L);
        book.add(3L, Side.BID, 10_100L, 30L);

        MutableLevel level = new MutableLevel();
        assertTrue(book.getByLevel(Side.BID, 0, level));
        assertEquals(10_100L, level.price);

        assertTrue(book.getByLevel(Side.BID, 1, level));
        assertEquals(10_000L, level.price);

        assertTrue(book.getByLevel(Side.BID, 2, level));
        assertEquals(9_900L, level.price);

        // Past end:
        assertFalse(book.getByLevel(Side.BID, 3, level));
    }

    @Test
    void getByLevelHolderNegativeThrows() {
        MutableLevel level = new MutableLevel();
        assertThrows(IndexOutOfBoundsException.class,
            () -> book.getByLevel(Side.BID, -1, level));
    }

    // --- forEachLevel --------------------------------------------------------

    @Test
    void forEachLevelEmitsBidsHighToLow() {
        book.add(1L, Side.BID, 9_900L, 10L);
        book.add(2L, Side.BID, 10_000L, 20L);
        book.add(3L, Side.BID, 9_800L, 30L);

        List<long[]> levels = new ArrayList<>();
        book.forEachLevel(Side.BID,
            (s, p, c, q) -> levels.add(new long[] { p, c, q }));

        assertEquals(3, levels.size());
        assertEquals(10_000L, levels.get(0)[0]);
        assertEquals(9_900L, levels.get(1)[0]);
        assertEquals(9_800L, levels.get(2)[0]);
    }

    @Test
    void forEachLevelEmitsAsksLowToHigh() {
        book.add(1L, Side.ASK, 10_300L, 10L);
        book.add(2L, Side.ASK, 10_100L, 20L);
        book.add(3L, Side.ASK, 10_200L, 30L);

        List<Long> prices = new ArrayList<>();
        book.forEachLevel(Side.ASK, (s, p, c, q) -> prices.add(p));

        assertEquals(List.of(10_100L, 10_200L, 10_300L), prices);
    }

    @Test
    void forEachLevelEmitsCorrectAggregates() {
        book.add(1L, Side.BID, 10_000L, 30L);
        book.add(2L, Side.BID, 10_000L, 70L);
        book.add(3L, Side.BID, 9_900L, 100L);

        long[] firstLevel = new long[3];
        long[] secondLevel = new long[3];
        int[] cursor = { 0 };
        book.forEachLevel(Side.BID, (s, p, c, q) -> {
            long[] target = cursor[0] == 0 ? firstLevel : secondLevel;
            target[0] = p;
            target[1] = c;
            target[2] = q;
            cursor[0]++;
        });
        assertEquals(10_000L, firstLevel[0]);
        assertEquals(2L, firstLevel[1]);
        assertEquals(100L, firstLevel[2]);
        assertEquals(9_900L, secondLevel[0]);
        assertEquals(1L, secondLevel[1]);
        assertEquals(100L, secondLevel[2]);
    }

    @Test
    void forEachLevelOnEmptySideDoesNothing() {
        int[] invocations = { 0 };
        book.forEachLevel(Side.BID, (s, p, c, q) -> invocations[0]++);
        assertEquals(0, invocations[0]);
    }

    // --- forEachOrderAtPrice -------------------------------------------------

    @Test
    void forEachOrderAtPriceEmitsFifoOrder() {
        book.add(1L, Side.BID, 10_000L, 30L);
        book.add(2L, Side.BID, 10_000L, 70L);
        book.add(3L, Side.BID, 10_000L, 100L);

        List<Long> ids = new ArrayList<>();
        List<Long> qtys = new ArrayList<>();
        boolean found = book.forEachOrderAtPrice(Side.BID, 10_000L,
            (id, s, p, q) -> {
                ids.add(id);
                qtys.add(q);
            });
        assertTrue(found);
        assertEquals(List.of(1L, 2L, 3L), ids);
        assertEquals(List.of(30L, 70L, 100L), qtys);
    }

    @Test
    void forEachOrderAtPriceReturnsFalseOnMissingLevel() {
        int[] invocations = { 0 };
        boolean found = book.forEachOrderAtPrice(Side.BID, 10_000L,
            (id, s, p, q) -> invocations[0]++);
        assertFalse(found);
        assertEquals(0, invocations[0]);
    }

    @Test
    void forEachOrderAtPriceUsesCorrectSide() {
        book.add(1L, Side.BID, 10_000L, 50L);
        // Looking on the wrong side: should miss even though that price exists on BID.
        int[] invocations = { 0 };
        boolean found = book.forEachOrderAtPrice(Side.ASK, 10_000L,
            (id, s, p, q) -> invocations[0]++);
        assertFalse(found);
        assertEquals(0, invocations[0]);
    }

    // --- Convenience API still works (via L3OrderBook defaults) --------------

    @Test
    void convenienceGetByPriceStillReturnsMaterializedSnapshot() {
        book.add(1L, Side.BID, 10_000L, 50L);
        book.add(2L, Side.BID, 10_000L, 30L);

        // Default method materializes — orders is a snapshot list of 2.
        var level = book.getByPrice(Side.BID, 10_000L);
        assertEquals(2, level.orderCount());
        assertEquals(80L, level.totalQuantity());
        assertEquals(2, level.orders().size());
    }

    // --- Ranged forEachLevel(side, from, limit, consumer) --------------------

    @Test
    void forEachLevelRangeTopN() {
        // 5 bid levels: best=10_100, then 10_000, 9_900, 9_800, 9_700.
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_100L - i * 100L, 10L);
        }
        List<Long> prices = new ArrayList<>();
        int emitted = book.forEachLevel(Side.BID, 0, 3,
            (s, p, c, q) -> prices.add(p));
        assertEquals(3, emitted);
        assertEquals(List.of(10_100L, 10_000L, 9_900L), prices);
    }

    @Test
    void forEachLevelRangeMiddleSlice() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_100L - i * 100L, 10L);
        }
        List<Long> prices = new ArrayList<>();
        int emitted = book.forEachLevel(Side.BID, 2, 2,
            (s, p, c, q) -> prices.add(p));
        assertEquals(2, emitted);
        assertEquals(List.of(9_900L, 9_800L), prices);
    }

    @Test
    void forEachLevelRangeOverflowsAvailable() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_100L - i * 100L, 10L);
        }
        List<Long> prices = new ArrayList<>();
        int emitted = book.forEachLevel(Side.BID, 0, 100,
            (s, p, c, q) -> prices.add(p));
        assertEquals(5, emitted);
        assertEquals(5, prices.size());
    }

    @Test
    void forEachLevelRangeFromBeyondEnd() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_100L - i * 100L, 10L);
        }
        int[] invocations = { 0 };
        int emitted = book.forEachLevel(Side.BID, 99, 10,
            (s, p, c, q) -> invocations[0]++);
        assertEquals(0, emitted);
        assertEquals(0, invocations[0]);
    }

    @Test
    void forEachLevelRangeZeroLimit() {
        book.add(1L, Side.BID, 10_000L, 50L);
        int[] invocations = { 0 };
        int emitted = book.forEachLevel(Side.BID, 0, 0,
            (s, p, c, q) -> invocations[0]++);
        assertEquals(0, emitted);
        assertEquals(0, invocations[0]);
    }

    @Test
    void forEachLevelRangeRejectsNegativeFrom() {
        LevelConsumer noop = (s, p, c, q) -> { };
        assertThrows(IndexOutOfBoundsException.class,
            () -> book.forEachLevel(Side.BID, -1, 5, noop));
    }

    @Test
    void forEachLevelRangeRejectsNegativeLimit() {
        LevelConsumer noop = (s, p, c, q) -> { };
        assertThrows(IllegalArgumentException.class,
            () -> book.forEachLevel(Side.BID, 0, -1, noop));
    }

    @Test
    void forEachLevelRangeSpansMultipleChunks() {
        // Straddle a chunk boundary: logical 4095 and 4096 land in different chunks.
        long base = PriceChunk.CHUNK_SIZE;
        book.add(1L, Side.ASK, base - 1, 10L);
        book.add(2L, Side.ASK, base,     20L);
        book.add(3L, Side.ASK, base + 1, 30L);

        List<Long> prices = new ArrayList<>();
        int emitted = book.forEachLevel(Side.ASK, 0, 10,
            (s, p, c, q) -> prices.add(p));
        assertEquals(3, emitted);
        assertEquals(List.of(base - 1, base, base + 1), prices);
    }

    @Test
    void forEachLevelRangeSkipsWholeChunksBeforeFrom() {
        // Pack one chunk with 3 ask levels just before the boundary; one chunk
        // with 2 ask levels just after. With from=3 limit=2, only the second
        // chunk's levels should be emitted.
        long base = PriceChunk.CHUNK_SIZE;
        book.add(1L, Side.ASK, base - 3, 10L);
        book.add(2L, Side.ASK, base - 2, 10L);
        book.add(3L, Side.ASK, base - 1, 10L);
        book.add(4L, Side.ASK, base + 1, 10L);
        book.add(5L, Side.ASK, base + 2, 10L);

        List<Long> prices = new ArrayList<>();
        int emitted = book.forEachLevel(Side.ASK, 3, 5,
            (s, p, c, q) -> prices.add(p));
        assertEquals(2, emitted);
        assertEquals(List.of(base + 1, base + 2), prices);
    }

    @Test
    void getLevelsConvenienceWrapsRange() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_100L - i * 100L, 10L);
        }
        var snapshots = book.getLevels(Side.BID, 0, 3);
        assertEquals(3, snapshots.size());
        assertEquals(10_100L, snapshots.get(0).price());
        assertEquals(10_000L, snapshots.get(1).price());
        assertEquals(9_900L,  snapshots.get(2).price());
    }

    @Test
    void forEachLevelFullSideStillWorksViaDefault() {
        // The void forEachLevel(side, consumer) is now an interface default
        // that delegates to the ranged form with limit=Integer.MAX_VALUE.
        // Regression guard that existing callers keep working.
        book.add(1L, Side.BID, 10_000L, 50L);
        book.add(2L, Side.BID, 9_900L, 30L);
        List<Long> prices = new ArrayList<>();
        book.forEachLevel(Side.BID, (s, p, c, q) -> prices.add(p));
        assertEquals(List.of(10_000L, 9_900L), prices);
    }
}
