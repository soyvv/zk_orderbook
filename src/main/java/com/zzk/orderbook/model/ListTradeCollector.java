package com.zzk.orderbook.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link TradeListener} that accumulates emitted trades into a {@link List}.
 * Used by the default {@code L3OrderBook.add(...)} list API; not for the
 * allocation-sensitive hot path.
 */
public final class ListTradeCollector implements TradeListener {

    private ArrayList<Trade> trades;

    @Override
    public void onTrade(long makerOrderId, long takerOrderId, Side takerSide,
                        long price, long quantity) {
        if (trades == null) {
            trades = new ArrayList<>();
        }
        trades.add(new Trade(makerOrderId, takerOrderId, takerSide, price, quantity));
    }

    public List<Trade> trades() {
        return trades == null ? Collections.emptyList() : trades;
    }
}
