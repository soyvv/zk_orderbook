package com.zzk.orderbook.core;

import com.zzk.orderbook.core.impl.TreeMapOrderBook;
import com.zzk.orderbook.core.v1.ChunkedBookConfig;
import com.zzk.orderbook.core.v1.ChunkedOrderBook;
import com.zzk.orderbook.model.BookSnapshotLevel;
import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the same randomized op stream into {@link TreeMapOrderBook} (reference)
 * and each {@link ChunkedOrderBook} directory variant (subject); after each op
 * asserts both produce the same trades, depth, orderCount, and snapshot.
 */
class RandomizedParityTest {

    private static final long SEED = 42L;
    private static final int OPS = 50_000;
    private static final long MID_PRICE = 1_000_000L;
    private static final int PRICE_HALF_RANGE_TICKS = 500;
    private static final int MAX_QTY = 100;

    private static final long ASK_ORIGIN = MID_PRICE - PRICE_HALF_RANGE_TICKS - 100;
    private static final long BID_MAX = MID_PRICE + PRICE_HALF_RANGE_TICKS + 100;

    @Test
    void randomizedStreamMatchesReferenceTreeMap() {
        runParity(treeMapConfig());
    }

    @Test
    void randomizedStreamMatchesReferenceArray() {
        runParity(arrayConfig());
    }

    private static ChunkedBookConfig treeMapConfig() {
        return ChunkedBookConfig.treeMap(
            PrecisionSpec.of(0, 1L, 0, 1L),
            ASK_ORIGIN, BID_MAX, OPS);
    }

    private static ChunkedBookConfig arrayConfig() {
        // Both sides use the same anchor → logical-index range covers
        // BID_MAX - ASK_ORIGIN ticks. Divide by chunk size (4096) for chunk range.
        int chunkRange = (int) (((BID_MAX - ASK_ORIGIN) >>> 12) + 1);
        return ChunkedBookConfig.array(
            PrecisionSpec.of(0, 1L, 0, 1L),
            ASK_ORIGIN, BID_MAX, OPS,
            /* minChunkId = */ 0,
            /* maxChunkId = */ chunkRange - 1,
            /* chunkPoolCapacity = */ chunkRange);
    }

    private void runParity(ChunkedBookConfig subjectConfig) {
        L3OrderBook ref = new TreeMapOrderBook(subjectConfig.precisionSpec());
        L3OrderBook sub = new ChunkedOrderBook(subjectConfig);

        Random rng = new Random(SEED);
        ArrayList<Long> activeIds = new ArrayList<>();
        HashMap<Long, Integer> activePositions = new HashMap<>();
        long nextOrderId = 1;

        for (int seq = 0; seq < OPS; seq++) {
            Op op = chooseOp(rng, activeIds.isEmpty());
            try {
                switch (op) {
                    case ADD -> {
                        long orderId = nextOrderId++;
                        Side side = rng.nextBoolean() ? Side.BID : Side.ASK;
                        long price = MID_PRICE + rng.nextInt(2 * PRICE_HALF_RANGE_TICKS + 1)
                            - PRICE_HALF_RANGE_TICKS;
                        long qty = 1 + rng.nextInt(MAX_QTY);

                        List<Trade> rt = ref.add(orderId, side, price, qty);
                        List<Trade> st = sub.add(orderId, side, price, qty);
                        assertEqualsTrades(rt, st, seq, op);

                        for (Trade t : rt) {
                            if (ref.getOrder(t.makerOrderId()) == null) {
                                removeActive(activeIds, activePositions, t.makerOrderId());
                            }
                        }
                        if (ref.getOrder(orderId) != null) {
                            addActive(activeIds, activePositions, orderId);
                        }
                    }
                    case UPDATE -> {
                        long orderId = activeIds.get(rng.nextInt(activeIds.size()));
                        long qty = rng.nextInt(MAX_QTY + 1);
                        ref.update(orderId, qty);
                        sub.update(orderId, qty);
                        if (qty == 0) {
                            removeActive(activeIds, activePositions, orderId);
                        }
                    }
                    case REMOVE -> {
                        long orderId = activeIds.get(rng.nextInt(activeIds.size()));
                        ref.remove(orderId);
                        sub.remove(orderId);
                        removeActive(activeIds, activePositions, orderId);
                    }
                }
            } catch (Throwable t) {
                fail("op #" + seq + " (" + op + ") raised: " + t.getMessage(), t);
            }

            assertStateMatches(ref, sub, seq, op);
        }
    }

    private enum Op { ADD, UPDATE, REMOVE }

    private static Op chooseOp(Random rng, boolean forceAdd) {
        if (forceAdd) {
            return Op.ADD;
        }
        double roll = rng.nextDouble();
        if (roll < 0.50) return Op.ADD;
        if (roll < 0.80) return Op.UPDATE;
        return Op.REMOVE;
    }

    private static void addActive(ArrayList<Long> ids, HashMap<Long, Integer> positions, long id) {
        positions.put(id, ids.size());
        ids.add(id);
    }

    private static void removeActive(ArrayList<Long> ids, HashMap<Long, Integer> positions, long id) {
        Integer pos = positions.remove(id);
        if (pos == null) return;
        int last = ids.size() - 1;
        long lastId = ids.remove(last);
        if (pos < last) {
            ids.set(pos, lastId);
            positions.put(lastId, pos);
        }
    }

    private static void assertEqualsTrades(List<Trade> ref, List<Trade> sub, int seq, Op op) {
        if (ref.size() != sub.size()) {
            fail("op #" + seq + " " + op + " trade size: ref=" + ref.size() + " sub=" + sub.size());
        }
        for (int i = 0; i < ref.size(); i++) {
            if (!ref.get(i).equals(sub.get(i))) {
                fail("op #" + seq + " " + op + " trade[" + i + "]: ref=" + ref.get(i)
                    + " sub=" + sub.get(i));
            }
        }
    }

    private static void assertStateMatches(L3OrderBook ref, L3OrderBook sub, int seq, Op op) {
        try {
            assertEquals(ref.depth(Side.BID), sub.depth(Side.BID), "bid depth");
            assertEquals(ref.depth(Side.ASK), sub.depth(Side.ASK), "ask depth");
            assertEquals(ref.orderCount(), sub.orderCount(), "orderCount");
            assertSnapshotEquals(ref, sub, Side.BID);
            assertSnapshotEquals(ref, sub, Side.ASK);
        } catch (AssertionError e) {
            fail("op #" + seq + " " + op + ": " + e.getMessage());
        }
    }

    private static void assertSnapshotEquals(L3OrderBook ref, L3OrderBook sub, Side side) {
        Iterator<BookSnapshotLevel> rIt = ref.snapshot(side).iterator();
        Iterator<BookSnapshotLevel> sIt = sub.snapshot(side).iterator();
        int idx = 0;
        while (rIt.hasNext() && sIt.hasNext()) {
            BookSnapshotLevel r = rIt.next();
            BookSnapshotLevel s = sIt.next();
            if (r.price() != s.price() || r.orderCount() != s.orderCount()
                || r.totalQuantity() != s.totalQuantity()) {
                fail(side + " snapshot[" + idx + "]: ref=" + r + " sub=" + s);
            }
            Order[] refOrders = ref.getByPrice(side, r.price()).orders().toArray(new Order[0]);
            Order[] subOrders = sub.getByPrice(side, s.price()).orders().toArray(new Order[0]);
            if (refOrders.length != subOrders.length) {
                fail(side + " level " + r.price() + " orderCount: ref=" + refOrders.length
                    + " sub=" + subOrders.length);
            }
            for (int i = 0; i < refOrders.length; i++) {
                if (refOrders[i].orderId() != subOrders[i].orderId()
                    || refOrders[i].quantity() != subOrders[i].quantity()) {
                    fail(side + " level " + r.price() + " order[" + i + "]: ref="
                        + refOrders[i] + " sub=" + subOrders[i]);
                }
            }
            idx++;
        }
        if (rIt.hasNext() || sIt.hasNext()) {
            fail(side + " snapshot length mismatch");
        }
    }
}
