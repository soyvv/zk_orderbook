package com.zzk.orderbook.core.ref;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.PriceLevel;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingTest {

    private static final PrecisionSpec BTC_USDT = PrecisionSpec.of(2, 1L, 8, 1L);

    private L3OrderBook book;

    @BeforeEach
    void setUp() {
        book = new TreeMapOrderBook(BTC_USDT);
    }

    @Test
    void addNoCrossReturnsEmptyTradesAndRests() {
        book.add(1, Side.ASK, 10_100, 100);
        List<Trade> trades = book.add(2, Side.BID, 10_000, 50);

        assertTrue(trades.isEmpty());
        assertEquals(1, book.depth(Side.BID));
        assertEquals(1, book.depth(Side.ASK));
        assertEquals(2, book.orderCount());
    }

    @Test
    void addFullyCrossesSingleMakerLeavesNoResidual() {
        book.add(1, Side.ASK, 10_100, 50);
        List<Trade> trades = book.add(2, Side.BID, 10_200, 50);

        assertEquals(1, trades.size());
        Trade t = trades.get(0);
        assertEquals(1L, t.makerOrderId());
        assertEquals(2L, t.takerOrderId());
        assertEquals(Side.BID, t.takerSide());
        assertEquals(10_100L, t.price());
        assertEquals(50L, t.quantity());

        assertEquals(0, book.depth(Side.ASK));
        assertEquals(0, book.depth(Side.BID));
        assertEquals(0, book.orderCount());
    }

    @Test
    void addPartiallyFillsSingleMakerNoResidualOnTaker() {
        book.add(1, Side.ASK, 10_100, 100);
        List<Trade> trades = book.add(2, Side.BID, 10_200, 30);

        assertEquals(1, trades.size());
        assertEquals(30L, trades.get(0).quantity());

        assertEquals(0, book.depth(Side.BID));
        assertEquals(1, book.depth(Side.ASK));
        assertEquals(1, book.orderCount());
        assertEquals(70L, book.getByPrice(Side.ASK, 10_100).totalQuantity());
        assertEquals(70L, book.getOrder(1).quantity());
    }

    @Test
    void addEatsAcrossMultipleLevelsBestFirst() {
        book.add(1, Side.ASK, 10_100, 20);
        book.add(2, Side.ASK, 10_200, 30);
        book.add(3, Side.ASK, 10_300, 50);

        List<Trade> trades = book.add(4, Side.BID, 10_250, 50);

        assertEquals(2, trades.size());
        assertEquals(10_100L, trades.get(0).price());
        assertEquals(20L, trades.get(0).quantity());
        assertEquals(10_200L, trades.get(1).price());
        assertEquals(30L, trades.get(1).quantity());

        assertEquals(0, book.depth(Side.BID));
        assertEquals(1, book.depth(Side.ASK));
        assertEquals(10_300L, book.getByLevel(Side.ASK, 0).price());
    }

    @Test
    void addExhaustsOppositeSideAndRestsResidual() {
        book.add(1, Side.ASK, 10_100, 30);
        book.add(2, Side.ASK, 10_200, 40);

        List<Trade> trades = book.add(3, Side.BID, 10_300, 100);

        assertEquals(2, trades.size());
        assertEquals(30L, trades.get(0).quantity());
        assertEquals(40L, trades.get(1).quantity());

        assertEquals(0, book.depth(Side.ASK));
        assertEquals(1, book.depth(Side.BID));
        PriceLevel resting = book.getByLevel(Side.BID, 0);
        assertEquals(10_300L, resting.price());
        assertEquals(30L, resting.totalQuantity());
        assertEquals(30L, book.getOrder(3).quantity());
        assertEquals(1, book.orderCount());
    }

    @Test
    void equalPriceCrosses() {
        book.add(1, Side.ASK, 10_100, 50);
        List<Trade> trades = book.add(2, Side.BID, 10_100, 50);

        assertEquals(1, trades.size());
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(0, book.depth(Side.BID));
    }

    @Test
    void fifoWithinLevel() {
        book.add(1, Side.ASK, 10_100, 30);
        book.add(2, Side.ASK, 10_100, 40);
        book.add(3, Side.ASK, 10_100, 50);

        List<Trade> trades = book.add(4, Side.BID, 10_100, 60);

        assertEquals(2, trades.size());
        assertEquals(1L, trades.get(0).makerOrderId());
        assertEquals(30L, trades.get(0).quantity());
        assertEquals(2L, trades.get(1).makerOrderId());
        assertEquals(30L, trades.get(1).quantity());

        assertNull(book.getOrder(1));
        assertEquals(10L, book.getOrder(2).quantity());
        assertEquals(50L, book.getOrder(3).quantity());
        assertEquals(60L, book.getByPrice(Side.ASK, 10_100).totalQuantity());
    }

    @Test
    void tradeRecordFields() {
        book.add(11, Side.ASK, 10_500, 25);
        List<Trade> trades = book.add(22, Side.BID, 10_600, 10);

        Trade t = trades.get(0);
        assertEquals(11L, t.makerOrderId());
        assertEquals(22L, t.takerOrderId());
        assertEquals(Side.BID, t.takerSide());
        assertEquals(10_500L, t.price());
        assertEquals(10L, t.quantity());
    }

    @Test
    void duplicateOrderIdRejectedBeforeMatching() {
        book.add(1, Side.ASK, 10_100, 50);
        book.add(2, Side.BID, 9_900, 50);

        assertThrows(IllegalStateException.class,
                () -> book.add(2, Side.BID, 10_200, 30));

        assertEquals(1, book.depth(Side.ASK));
        assertEquals(50L, book.getOrder(1).quantity());
    }

    @Test
    void askTakerCrossesBidsBestFirst() {
        book.add(1, Side.BID, 10_000, 30);
        book.add(2, Side.BID, 9_900, 40);
        book.add(3, Side.BID, 9_800, 50);

        List<Trade> trades = book.add(4, Side.ASK, 9_850, 50);

        assertEquals(2, trades.size());
        assertEquals(10_000L, trades.get(0).price());
        assertEquals(30L, trades.get(0).quantity());
        assertEquals(9_900L, trades.get(1).price());
        assertEquals(20L, trades.get(1).quantity());
        assertEquals(Side.ASK, trades.get(0).takerSide());

        assertEquals(2, book.depth(Side.BID));
        assertEquals(9_900L, book.getByLevel(Side.BID, 0).price());
        assertEquals(20L, book.getOrder(2).quantity());
        assertEquals(0, book.depth(Side.ASK));
    }
}
