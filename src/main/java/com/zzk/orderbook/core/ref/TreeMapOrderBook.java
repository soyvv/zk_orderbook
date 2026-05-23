package com.zzk.orderbook.core.ref;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.model.LevelConsumer;
import com.zzk.orderbook.model.MutableLevel;
import com.zzk.orderbook.model.MutableOrder;
import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.OrderConsumer;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.PriceLevel;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.TradeListener;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reference implementation: TreeMap per side + HashMap order index.
 * See analysis.md §4-6 for design and complexity targets.
 */
public final class TreeMapOrderBook implements L3OrderBook {

    private final PrecisionSpec spec;
    private final NavigableMap<Long, MutablePriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, MutablePriceLevel> asks = new TreeMap<>();
    private final OrderIndex index = new HashMapOrderIndex();

    public TreeMapOrderBook(PrecisionSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public PrecisionSpec precisionSpec() {
        return spec;
    }

    @Override
    public void reset() {
        // TreeMap.clear() drops Entry references; subsequent puts will re-allocate
        // Entry objects. Not allocation-free — that's inherent to TreeMap.
        bids.clear();
        asks.clear();
        index.clear();
    }

    @Override
    public void add(long orderId, Side side, long price, long quantity, TradeListener listener) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("add requires quantity > 0; got " + quantity);
        }
        if (index.contains(orderId)) {
            throw new IllegalStateException("orderId already active: " + orderId);
        }

        long remaining = quantity;
        NavigableMap<Long, MutablePriceLevel> oppBook = bookFor(opposite(side));

        while (remaining > 0 && !oppBook.isEmpty()) {
            Map.Entry<Long, MutablePriceLevel> bestEntry = oppBook.firstEntry();
            long oppPrice = bestEntry.getKey();
            boolean crosses = (side == Side.BID) ? oppPrice <= price : oppPrice >= price;
            if (!crosses) {
                break;
            }

            MutablePriceLevel level = bestEntry.getValue();
            Iterator<Map.Entry<Long, Order>> it = level.orderMap().entrySet().iterator();
            while (it.hasNext() && remaining > 0) {
                Order maker = it.next().getValue();
                long fillQty = Math.min(remaining, maker.quantity());
                listener.onTrade(maker.orderId(), orderId, side, oppPrice, fillQty);
                if (fillQty == maker.quantity()) {
                    it.remove();
                    index.remove(maker.orderId());
                } else {
                    maker.setQuantity(maker.quantity() - fillQty);
                }
                level.totalQuantity -= fillQty;
                remaining -= fillQty;
            }

            if (level.isEmpty()) {
                oppBook.remove(oppPrice);
            }
        }

        if (remaining > 0) {
            NavigableMap<Long, MutablePriceLevel> book = bookFor(side);
            MutablePriceLevel level = book.computeIfAbsent(price, p -> new MutablePriceLevel(p, side));
            Order order = new Order(orderId, side, price, remaining);
            level.addOrder(order);
            index.put(orderId, new OrderRef(order, side, price));
        }
    }

    private static Side opposite(Side side) {
        return side == Side.BID ? Side.ASK : Side.BID;
    }

    @Override
    public void update(long orderId, long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("update quantity must be >= 0; got " + quantity);
        }
        if (quantity == 0) {
            remove(orderId);
            return;
        }
        OrderRef ref = index.get(orderId);
        if (ref == null) {
            throw new IllegalArgumentException("unknown orderId: " + orderId);
        }
        MutablePriceLevel level = bookFor(ref.side()).get(ref.price());
        level.updateQuantity(orderId, quantity);
    }

    @Override
    public void remove(long orderId) {
        OrderRef ref = index.remove(orderId);
        if (ref == null) {
            throw new IllegalArgumentException("unknown orderId: " + orderId);
        }
        NavigableMap<Long, MutablePriceLevel> book = bookFor(ref.side());
        MutablePriceLevel level = book.get(ref.price());
        level.removeOrder(orderId);
        if (level.isEmpty()) {
            book.remove(ref.price());
        }
    }

    @Override
    public boolean getOrder(long orderId, MutableOrder out) {
        OrderRef ref = index.get(orderId);
        if (ref == null) {
            return false;
        }
        Order o = ref.order();
        out.orderId = o.orderId();
        out.side = o.side();
        out.price = o.price();
        out.quantity = o.quantity();
        return true;
    }

    @Override
    public boolean getByPrice(Side side, long price, MutableLevel out) {
        MutablePriceLevel level = bookFor(side).get(price);
        if (level == null) {
            return false;
        }
        out.side = level.side();
        out.price = level.price();
        out.orderCount = level.orderCount();
        out.totalQuantity = level.totalQuantity();
        return true;
    }

    @Override
    public boolean getByLevel(Side side, int levelIndex, MutableLevel out) {
        if (levelIndex < 0) {
            throw new IndexOutOfBoundsException("levelIndex < 0: " + levelIndex);
        }
        int i = 0;
        for (MutablePriceLevel level : bookFor(side).values()) {
            if (i++ == levelIndex) {
                out.side = level.side();
                out.price = level.price();
                out.orderCount = level.orderCount();
                out.totalQuantity = level.totalQuantity();
                return true;
            }
        }
        return false;
    }

    @Override
    public void forEachLevel(Side side, LevelConsumer consumer) {
        for (MutablePriceLevel level : bookFor(side).values()) {
            consumer.onLevel(level.side(), level.price(),
                level.orderCount(), level.totalQuantity());
        }
    }

    @Override
    public boolean forEachOrderAtPrice(Side side, long price, OrderConsumer consumer) {
        MutablePriceLevel level = bookFor(side).get(price);
        if (level == null) {
            return false;
        }
        for (Order o : level.orderMap().values()) {
            consumer.onOrder(o.orderId(), o.side(), o.price(), o.quantity());
        }
        return true;
    }

    @Override
    public int trim(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0; got " + depth);
        }
        return trimSide(bids, depth) + trimSide(asks, depth);
    }

    @Override
    public int depth(Side side) {
        return bookFor(side).size();
    }

    @Override
    public int orderCount() {
        return index.size();
    }

    private NavigableMap<Long, MutablePriceLevel> bookFor(Side side) {
        return side == Side.BID ? bids : asks;
    }

    private int trimSide(NavigableMap<Long, MutablePriceLevel> book, int depth) {
        if (book.size() <= depth) {
            return 0;
        }
        int removed = 0;
        int kept = 0;
        Iterator<MutablePriceLevel> it = book.values().iterator();
        while (it.hasNext()) {
            MutablePriceLevel lvl = it.next();
            if (kept < depth) {
                kept++;
                continue;
            }
            for (Order o : lvl.orderMap().values()) {
                index.remove(o.orderId());
                removed++;
            }
            it.remove();
        }
        return removed;
    }

    /** Internal mutable price level. Exposed only as the read-only {@link PriceLevel} view. */
    private static final class MutablePriceLevel implements PriceLevel {

        private final long price;
        private final Side side;
        private final LinkedHashMap<Long, Order> orders = new LinkedHashMap<>();
        private long totalQuantity;

        MutablePriceLevel(long price, Side side) {
            this.price = price;
            this.side = side;
        }

        @Override
        public long price() {
            return price;
        }

        @Override
        public Side side() {
            return side;
        }

        @Override
        public int orderCount() {
            return orders.size();
        }

        @Override
        public long totalQuantity() {
            return totalQuantity;
        }

        @Override
        public Collection<Order> orders() {
            return Collections.unmodifiableCollection(orders.values());
        }

        LinkedHashMap<Long, Order> orderMap() {
            return orders;
        }

        void addOrder(Order order) {
            orders.put(order.orderId(), order);
            totalQuantity += order.quantity();
        }

        void removeOrder(long orderId) {
            Order removed = orders.remove(orderId);
            if (removed != null) {
                totalQuantity -= removed.quantity();
            }
        }

        void updateQuantity(long orderId, long newQuantity) {
            Order o = orders.get(orderId);
            if (o == null) {
                throw new IllegalStateException("order missing from level: " + orderId);
            }
            totalQuantity += newQuantity - o.quantity();
            o.setQuantity(newQuantity);
        }

        boolean isEmpty() {
            return orders.isEmpty();
        }
    }
}
