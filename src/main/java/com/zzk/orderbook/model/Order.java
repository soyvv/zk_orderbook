package com.zzk.orderbook.model;

/**
 * Single L3 order. Mutable quantity so updates avoid allocation.
 */
public final class Order {

    private final long orderId;
    private final Side side;
    private final long price;
    private long quantity;

    public Order(long orderId, Side side, long price, long quantity) {
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
    }

    public long orderId() {
        return orderId;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }
}
