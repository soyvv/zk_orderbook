package com.zzk.orderbook.core.v1;

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

class ChunkedMatchingTest {

    private static final PrecisionSpec SPEC = PrecisionSpec.of(0, 1L, 0, 1L);

    private ChunkedOrderBook book;

    @BeforeEach
    void setUp() {
        book = new ChunkedOrderBook(ChunkedBookConfig.treeMap(
            SPEC, 0L, 2_000_000L, 1024));
    }

    @Test
    void addNoCrossReturnsEmptyTradesAndRests() {
        book.add(1, Side.ASK, 10_100, 100);
        List<Trade> trades = book.add(2, Side.BID, 10_000, 50);

        assertTrue(trades.isEmpty());
        assertEquals(1, book.depth(Side.BID));
        assertEquals(1, book.depth(Side.ASK));
        assertEquals(2, book.orderCount());
        book.assertInternalInvariants();
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
        book.assertInternalInvariants();
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
        book.assertInternalInvariants();
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
        assertEquals(10_300L, book.askSideForTest().bestPriceTick());
        book.assertInternalInvariants();
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
        PriceLevel resting = book.getByPrice(Side.BID, 10_300);
        assertEquals(10_300L, resting.price());
        assertEquals(30L, resting.totalQuantity());
        assertEquals(30L, book.getOrder(3).quantity());
        assertEquals(1, book.orderCount());
        book.assertInternalInvariants();
    }

    @Test
    void equalPriceCrosses() {
        book.add(1, Side.ASK, 10_100, 50);
        List<Trade> trades = book.add(2, Side.BID, 10_100, 50);

        assertEquals(1, trades.size());
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(0, book.depth(Side.BID));
        book.assertInternalInvariants();
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
        book.assertInternalInvariants();
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
        book.assertInternalInvariants();
    }

    @Test
    void duplicateOrderIdRejectedBeforeMatching() {
        book.add(1, Side.ASK, 10_100, 50);
        book.add(2, Side.BID, 9_900, 50);

        assertThrows(IllegalStateException.class,
                () -> book.add(2, Side.BID, 10_200, 30));

        assertEquals(1, book.depth(Side.ASK));
        assertEquals(50L, book.getOrder(1).quantity());
        book.assertInternalInvariants();
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
        assertEquals(9_900L, book.bidSideForTest().bestPriceTick());
        assertEquals(20L, book.getOrder(2).quantity());
        assertEquals(0, book.depth(Side.ASK));
        book.assertInternalInvariants();
    }

    @Test
    void matchAcrossChunkBoundary() {
        // Set up asks straddling a chunk boundary (logical 4095, 4096, 4097).
        long base = PriceChunk.CHUNK_SIZE;
        book.add(1, Side.ASK, base - 1, 10);
        book.add(2, Side.ASK, base,     20);
        book.add(3, Side.ASK, base + 1, 30);

        // Taker eats all 60 ask qty exactly.
        List<Trade> trades = book.add(4, Side.BID, base + 5, 60);

        assertEquals(3, trades.size());
        assertEquals(base - 1, trades.get(0).price());
        assertEquals(base,     trades.get(1).price());
        assertEquals(base + 1, trades.get(2).price());
        assertEquals(10L + 20L + 30L, trades.stream().mapToLong(Trade::quantity).sum());

        // Both sides drain — verifies chunk removal across the boundary.
        assertEquals(0, book.depth(Side.ASK));
        assertEquals(0, book.depth(Side.BID));
        book.assertInternalInvariants();
    }
}
