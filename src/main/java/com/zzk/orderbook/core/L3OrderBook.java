package com.zzk.orderbook.core;

import com.zzk.orderbook.model.BookSnapshotLevel;
import com.zzk.orderbook.model.ImmutablePriceLevel;
import com.zzk.orderbook.model.LevelConsumer;
import com.zzk.orderbook.model.ListTradeCollector;
import com.zzk.orderbook.model.MutableLevel;
import com.zzk.orderbook.model.MutableOrder;
import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.OrderConsumer;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.PriceLevel;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;
import com.zzk.orderbook.model.TradeListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-instrument L3 order book contract.
 *
 * <p>The interface is layered:
 * <ul>
 *   <li><b>Mutation API</b> — {@link #add(long, Side, long, long, TradeListener)},
 *       {@link #update}, {@link #remove}. Implementations must keep these
 *       allocation-free (use {@link TradeListener#DISCARD} to drop trades).</li>
 *   <li><b>Hot query API</b> — required abstract methods that read into
 *       caller-owned holders ({@link MutableOrder}, {@link MutableLevel}) or
 *       emit primitives via visitors ({@link LevelConsumer},
 *       {@link OrderConsumer}). Implementations should make these
 *       allocation-free where the backing structure allows.</li>
 *   <li><b>Convenience query API</b> — {@link #getOrder(long)},
 *       {@link #getByPrice(Side, long)}, {@link #getByLevel(Side, int)},
 *       {@link #snapshot(Side)}, {@link #levels(Side)}. Provided as default
 *       methods that build immutable snapshot objects from the hot API.
 *       Convenient for tests and ad-hoc inspection; not for hot paths.</li>
 * </ul>
 *
 * <p>Conventions:
 * <ul>
 *   <li>level index 0 = best price on the given side: bids highest first;
 *       asks lowest first</li>
 *   <li>{@code qty == 0} on update implies removal</li>
 *   <li>empty price levels are auto-removed</li>
 *   <li>all operations assume single-threaded access</li>
 * </ul>
 */
public interface L3OrderBook {

    // --- Mutation API --------------------------------------------------------

    /**
     * Submit a limit order, emitting any resulting trades to {@code listener}.
     * Residual quantity rests at {@code price}.
     *
     * <p>Allocation-free primary API: implementations must not allocate a
     * {@code Trade} or list on this path. Use {@link TradeListener#DISCARD}
     * to drop trade output entirely.
     */
    void add(long orderId, Side side, long price, long quantity, TradeListener listener);

    /**
     * Convenience wrapper that collects emitted trades into a {@link List}.
     * Allocates a list + a {@code Trade} per fill — not for the hot path.
     */
    default List<Trade> add(long orderId, Side side, long price, long quantity) {
        ListTradeCollector collector = new ListTradeCollector();
        add(orderId, side, price, quantity, collector);
        return collector.trades();
    }

    void update(long orderId, long quantity);

    void remove(long orderId);

    // --- Hot query API (required, allocation-free where backing allows) ------

    /** Populate {@code out} with the order matching {@code orderId}; return true if found. */
    boolean getOrder(long orderId, MutableOrder out);

    /** Populate {@code out} with the level at {@code price} on {@code side}; return true if present. */
    boolean getByPrice(Side side, long price, MutableLevel out);

    /** Populate {@code out} with the level at rank {@code levelIndex}; return false if past end. */
    boolean getByLevel(Side side, int levelIndex, MutableLevel out);

    /** Walk all levels on {@code side} best-to-worst, calling {@code consumer} per level. */
    void forEachLevel(Side side, LevelConsumer consumer);

    /** Walk FIFO orders at {@code price} on {@code side}; return false if no such level. */
    boolean forEachOrderAtPrice(Side side, long price, OrderConsumer consumer);

    // --- Convenience query API (default — build immutable snapshots) ---------

    default Order getOrder(long orderId) {
        MutableOrder out = new MutableOrder();
        if (!getOrder(orderId, out)) {
            return null;
        }
        return new Order(out.orderId, out.side, out.price, out.quantity);
    }

    default PriceLevel getByPrice(Side side, long price) {
        MutableLevel m = new MutableLevel();
        if (!getByPrice(side, price, m)) {
            return null;
        }
        return materializeLevel(m.side, m.price, m.orderCount, m.totalQuantity);
    }

    default PriceLevel getByLevel(Side side, int levelIndex) {
        MutableLevel m = new MutableLevel();
        if (!getByLevel(side, levelIndex, m)) {
            return null;
        }
        return materializeLevel(m.side, m.price, m.orderCount, m.totalQuantity);
    }

    default Iterable<BookSnapshotLevel> snapshot(Side side) {
        ArrayList<BookSnapshotLevel> out = new ArrayList<>();
        forEachLevel(side, (s, p, c, q) -> out.add(new BookSnapshotLevel(s, p, c, q)));
        return out;
    }

    default Iterable<PriceLevel> levels(Side side) {
        ArrayList<PriceLevel> out = new ArrayList<>();
        forEachLevel(side, (s, p, c, q) -> out.add(materializeLevel(s, p, c, q)));
        return out;
    }

    /** Build an immutable level snapshot by walking the per-level FIFO once. */
    private PriceLevel materializeLevel(Side side, long price, int orderCount, long totalQuantity) {
        ArrayList<Order> orders = new ArrayList<>(orderCount);
        forEachOrderAtPrice(side, price,
            (id, s, p, q) -> orders.add(new Order(id, s, p, q)));
        return new ImmutablePriceLevel(side, price, orderCount, totalQuantity, orders);
    }

    // --- Misc ----------------------------------------------------------------

    /** Drop all levels beyond depth on each side; returns count of orders removed. */
    int trim(int depth);

    int depth(Side side);

    int orderCount();

    /** Per-instrument precision/refdata used by callers and the invariant checker. */
    PrecisionSpec precisionSpec();

    /**
     * Clear all book state, returning the book to its post-construction
     * configuration. Lets benchmarks and stress harnesses reuse a single
     * book instance across iterations without paying construction allocation
     * costs.
     *
     * <p>Array-backed implementations should perform this allocation-free.
     * Map-backed implementations may still allocate as their internal node
     * pools refill (e.g. {@code TreeMap.Entry}).
     */
    default void reset() {
        throw new UnsupportedOperationException(
            "reset() not implemented by " + getClass().getName());
    }
}
