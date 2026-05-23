package com.zzk.orderbook.core.ref;

import com.zzk.orderbook.core.L3OrderBook;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TreeMapOrderBookTest {

    /** Binance-BTCUSDT-style: price tick 0.01, qty step 0.00000001. */
    private static final PrecisionSpec BTC_USDT = PrecisionSpec.of(2, 1L, 8, 1L);

    private L3OrderBook book;

    @BeforeEach
    void setUp() {
        book = new TreeMapOrderBook(BTC_USDT);
    }

    @Test
    void addOrderOnEmptyBookCreatesLevel() {
        book.add(1, Side.BID, 10_000, 50);

        assertEquals(1, book.depth(Side.BID));
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(1, book.orderCount());

        PriceLevel level = book.getByLevel(Side.BID, 0);
        assertNotNull(level);
        assertEquals(10_000L, level.price());
        assertEquals(Side.BID, level.side());
        assertEquals(1, level.orderCount());
        assertEquals(50L, level.totalQuantity());

        PriceLevel byPrice = book.getByPrice(Side.BID, 10_000);
        assertNotNull(byPrice);
        assertEquals(level.price(), byPrice.price());
        assertEquals(level.side(), byPrice.side());
        assertEquals(level.orderCount(), byPrice.orderCount());
        assertEquals(level.totalQuantity(), byPrice.totalQuantity());
    }

    @Test
    void updateChangesQuantityWithoutRelocation() {
        book.add(1, Side.BID, 10_000, 50);
        book.update(1, 80);

        assertEquals(1, book.depth(Side.BID));
        assertEquals(1, book.orderCount());

        Order o = book.getOrder(1);
        assertNotNull(o);
        assertEquals(80L, o.quantity());
        assertEquals(80L, book.getByPrice(Side.BID, 10_000).totalQuantity());
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
    }

    @Test
    void removingLastOrderAtPriceRemovesLevel() {
        book.add(1, Side.BID, 10_000, 50);
        book.remove(1);

        assertEquals(0, book.depth(Side.BID));
        assertEquals(0, book.orderCount());
        assertNull(book.getByPrice(Side.BID, 10_000));
        assertNull(book.getOrder(1));
    }

    @Test
    void bidsSortedHighToLowAsksSortedLowToHigh() {
        // Non-overlapping ranges so add() doesn't cross.
        book.add(1, Side.BID, 9_900, 10);
        book.add(2, Side.BID, 10_000, 10);
        book.add(3, Side.BID, 9_800, 10);
        book.add(4, Side.ASK, 10_300, 10);
        book.add(5, Side.ASK, 10_100, 10);
        book.add(6, Side.ASK, 10_200, 10);

        assertEquals(List.of(10_000L, 9_900L, 9_800L), pricesOn(Side.BID));
        assertEquals(List.of(10_100L, 10_200L, 10_300L), pricesOn(Side.ASK));
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
    void getByPriceReturnsNullForMissingLevel() {
        assertNull(book.getByPrice(Side.BID, 10_000));

        book.add(1, Side.BID, 10_000, 50);
        assertNotNull(book.getByPrice(Side.BID, 10_000));
        assertNull(book.getByPrice(Side.BID, 10_001));
        assertNull(book.getByPrice(Side.ASK, 10_000));
    }

    @Test
    void trimRemovesLevelsBeyondDepth() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_000 - i * 100, 10);
            book.add(200 + i, Side.ASK, 10_100 + i * 100, 10);
        }
        assertEquals(5, book.depth(Side.BID));
        assertEquals(5, book.depth(Side.ASK));
        assertEquals(10, book.orderCount());

        int removed = book.trim(2);

        assertEquals(6, removed);
        assertEquals(2, book.depth(Side.BID));
        assertEquals(2, book.depth(Side.ASK));
        assertEquals(4, book.orderCount());
        assertEquals(10_000L, book.getByLevel(Side.BID, 0).price());
        assertEquals(9_900L, book.getByLevel(Side.BID, 1).price());
        assertEquals(10_100L, book.getByLevel(Side.ASK, 0).price());
        assertEquals(10_200L, book.getByLevel(Side.ASK, 1).price());
    }

    @Test
    void trimIsIdempotent() {
        for (int i = 0; i < 5; i++) {
            book.add(100 + i, Side.BID, 10_000 - i * 100, 10);
        }
        book.trim(3);
        int secondRemoved = book.trim(3);

        assertEquals(0, secondRemoved);
        assertEquals(3, book.depth(Side.BID));
    }

    private List<Long> pricesOn(Side side) {
        List<Long> out = new ArrayList<>();
        for (PriceLevel lvl : book.levels(side)) {
            out.add(lvl.price());
        }
        return out;
    }

    // --- Ranged forEachLevel(side, from, limit, consumer) --------------------

    @Test
    void forEachLevelRangeTopN() {
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
}
