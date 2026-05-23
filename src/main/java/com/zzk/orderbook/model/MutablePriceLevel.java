package com.zzk.orderbook.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Mutable {@link PriceLevel} backed by a {@link LinkedHashMap} of orders in
 * FIFO insertion order. Used by tree-style order book implementations as the
 * per-price-bucket data structure; exposed through the {@link PriceLevel}
 * interface for callers, with mutation methods (add / remove / updateQuantity
 * / addToTotalQuantity) reserved for the owning book.
 *
 * <p>{@link #orders()} returns an unmodifiable view; callers must not iterate
 * concurrently with mutation.
 */
public final class MutablePriceLevel implements PriceLevel {

    private final long price;
    private final Side side;
    private final LinkedHashMap<Long, Order> orders = new LinkedHashMap<>();
    private long totalQuantity;

    public MutablePriceLevel(long price, Side side) {
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

    /**
     * Live FIFO map of orders. Intended for the owning book's hot path
     * (iterator-based remove during matching/trim); callers must not mutate
     * the returned map outside of {@link #removeOrder} / {@link #addOrder}
     * unless they also keep {@link #totalQuantity()} consistent.
     */
    public LinkedHashMap<Long, Order> orderMap() {
        return orders;
    }

    public void addOrder(Order order) {
        orders.put(order.orderId(), order);
        totalQuantity += order.quantity();
    }

    public void removeOrder(long orderId) {
        Order removed = orders.remove(orderId);
        if (removed != null) {
            totalQuantity -= removed.quantity();
        }
    }

    public void updateQuantity(long orderId, long newQuantity) {
        Order o = orders.get(orderId);
        if (o == null) {
            throw new IllegalStateException("order missing from level: " + orderId);
        }
        totalQuantity += newQuantity - o.quantity();
        o.setQuantity(newQuantity);
    }

    /**
     * Adjust {@link #totalQuantity()} without touching the FIFO map. Used by
     * matching loops that already hold the maker {@link Order} reference and
     * mutate its quantity directly; avoids a redundant map lookup vs
     * {@link #updateQuantity}.
     */
    public void addToTotalQuantity(long delta) {
        totalQuantity += delta;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }
}
