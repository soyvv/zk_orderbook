package com.zzk.orderbook.core.v1;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.model.LevelConsumer;
import com.zzk.orderbook.model.MutableLevel;
import com.zzk.orderbook.model.MutableOrder;
import com.zzk.orderbook.model.OrderConsumer;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.TradeListener;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

/**
 * Chunked sparse-ladder L3 book backed by primitive arrays. See
 * docs/low_latency_design_plan.md for the design and invariants.
 *
 * <p>Threading: single-threaded. State lives in primitive arrays inside
 * {@link OrderArena} and {@link PriceChunk}. The hot query API
 * ({@link #getOrder(long, MutableOrder)}, {@link #forEachLevel}, etc.) reads
 * from these directly without allocating; convenience object-returning APIs
 * (inherited as {@code default} from {@link L3OrderBook}) materialize
 * snapshots on demand.
 */
public final class ChunkedOrderBook implements L3OrderBook {

    private final ChunkedBookConfig config;
    private final OrderArena arena;
    private final OrderIdIndex idIndex;
    private final BookSide bidSide;
    private final BookSide askSide;

    /** Reusable scratch buffer for {@link #trimSide}; grows on demand. */
    private int[] trimScratch = new int[64];

    public ChunkedOrderBook(ChunkedBookConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.arena = new OrderArena(config.arenaCapacity());
        // Size the index for the configured arena; default load factor.
        // Agrona allocates one long[2 * nextPow2(cap/loadFactor)] at construction;
        // mid-run rehash is avoided as long as peak entries stay below the
        // resize threshold.
        this.idIndex = new AgronaLongLongIndex(config.arenaCapacity(), 0.75f);
        this.askSide = new BookSide(Side.ASK, config.askOriginTick(), false, directoryFor(config));
        this.bidSide = new BookSide(Side.BID, config.bidMaxTick(), true, directoryFor(config));
    }

    private static ChunkDirectory directoryFor(ChunkedBookConfig c) {
        return switch (c.directoryKind()) {
            case TREEMAP -> new TreeMapChunkDirectory();
            case ARRAY -> new ArrayChunkDirectory(
                c.minChunkId(), c.maxChunkId(),
                new ChunkPool(c.chunkPoolCapacity()));
        };
    }

    @Override
    public PrecisionSpec precisionSpec() {
        return config.precisionSpec();
    }

    @Override
    public void reset() {
        // Order matters: clear the directories (which release chunks back to
        // their pools) before resetting the arena (which invalidates every
        // arena handle by bumping generations). The idIndex clear is symmetric
        // — after this returns, idIndex.get(...) on any prior orderId yields
        // MISSING, the arena free-list is fully populated, and both sides'
        // best caches are at NO_CHUNK / NULL.
        bidSide.reset();
        askSide.reset();
        arena.reset();
        idIndex.clear();
    }

    // --- Mutation ------------------------------------------------------------

    @Override
    public void add(long orderId, Side side, long price, long quantity, TradeListener listener) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("add requires quantity > 0; got " + quantity);
        }
        if (idIndex.containsKey(orderId)) {
            throw new IllegalStateException("orderId already active: " + orderId);
        }

        long remaining = quantity;
        BookSide opp = sideFor(side == Side.BID ? Side.ASK : Side.BID);

        while (remaining > 0 && opp.hasBest()) {
            long makerPrice = opp.bestPriceTick();
            boolean crosses = (side == Side.BID) ? makerPrice <= price : makerPrice >= price;
            if (!crosses) {
                break;
            }

            PriceChunk chunk = opp.directory.get(opp.bestChunkId);
            int headSlot = chunk.headOrder[opp.bestOffset];
            long makerQty = arena.qty[headSlot];
            long fillQty = Math.min(remaining, makerQty);

            listener.onTrade(arena.orderId[headSlot], orderId, side, makerPrice, fillQty);

            if (fillQty == makerQty) {
                opp.removeOrder(arena, idIndex, headSlot);
            } else {
                opp.updateOrderQty(arena, headSlot, makerQty - fillQty);
            }
            remaining -= fillQty;
        }

        if (remaining > 0) {
            sideFor(side).appendOrder(arena, idIndex, orderId, price, remaining);
        }
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
        long handle = idIndex.get(orderId);
        if (handle == OrderIdIndex.MISSING) {
            throw new IllegalArgumentException("unknown orderId: " + orderId);
        }
        int slot = OrderArena.slotOf(handle);
        sideForTag(arena.side[slot]).updateOrderQty(arena, slot, quantity);
    }

    @Override
    public void remove(long orderId) {
        long handle = idIndex.get(orderId);
        if (handle == OrderIdIndex.MISSING) {
            throw new IllegalArgumentException("unknown orderId: " + orderId);
        }
        int slot = OrderArena.slotOf(handle);
        sideForTag(arena.side[slot]).removeOrder(arena, idIndex, slot);
    }

    // --- Hot query API (allocation-free) -------------------------------------

    @Override
    public boolean getOrder(long orderId, MutableOrder out) {
        long handle = idIndex.get(orderId);
        if (handle == OrderIdIndex.MISSING) {
            return false;
        }
        int slot = OrderArena.slotOf(handle);
        out.orderId = arena.orderId[slot];
        out.side = sideOf(arena.side[slot]);
        out.price = arena.priceTick[slot];
        out.quantity = arena.qty[slot];
        return true;
    }

    @Override
    public boolean getByPrice(Side side, long price, MutableLevel out) {
        BookSide bs = sideFor(side);
        long logical = bs.logicalIdxOf(price);
        if (logical < 0) {
            return false;
        }
        long chunkId = logical >>> PriceChunk.CHUNK_BITS;
        int offset = (int) (logical & PriceChunk.CHUNK_MASK);
        PriceChunk chunk = bs.directory.get(chunkId);
        if (chunk == null || !chunk.isLevelSet(offset)) {
            return false;
        }
        out.side = bs.side;
        out.price = price;
        out.orderCount = chunk.orderCount[offset];
        out.totalQuantity = chunk.totalQty[offset];
        return true;
    }

    @Override
    public boolean getByLevel(Side side, int levelIndex, MutableLevel out) {
        if (levelIndex < 0) {
            throw new IndexOutOfBoundsException("levelIndex < 0: " + levelIndex);
        }
        BookSide bs = sideFor(side);
        int rank = 0;
        Iterator<PriceChunk> chunkIt = bs.directory.iterator();
        while (chunkIt.hasNext()) {
            PriceChunk chunk = chunkIt.next();
            for (int offset = BitmapUtils.nextSetBit(chunk.levelBitmap, 0, PriceChunk.CHUNK_SIZE);
                 offset != BitmapUtils.NOT_FOUND;
                 offset = BitmapUtils.nextSetBit(chunk.levelBitmap, offset + 1, PriceChunk.CHUNK_SIZE)) {
                if (rank++ == levelIndex) {
                    out.side = bs.side;
                    out.price = bs.priceTickOf(chunk.chunkId, offset);
                    out.orderCount = chunk.orderCount[offset];
                    out.totalQuantity = chunk.totalQty[offset];
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void forEachLevel(Side side, LevelConsumer consumer) {
        BookSide bs = sideFor(side);
        Iterator<PriceChunk> chunkIt = bs.directory.iterator();
        while (chunkIt.hasNext()) {
            PriceChunk chunk = chunkIt.next();
            for (int offset = BitmapUtils.nextSetBit(chunk.levelBitmap, 0, PriceChunk.CHUNK_SIZE);
                 offset != BitmapUtils.NOT_FOUND;
                 offset = BitmapUtils.nextSetBit(chunk.levelBitmap, offset + 1, PriceChunk.CHUNK_SIZE)) {
                consumer.onLevel(bs.side,
                    bs.priceTickOf(chunk.chunkId, offset),
                    chunk.orderCount[offset],
                    chunk.totalQty[offset]);
            }
        }
    }

    @Override
    public boolean forEachOrderAtPrice(Side side, long price, OrderConsumer consumer) {
        BookSide bs = sideFor(side);
        long logical = bs.logicalIdxOf(price);
        if (logical < 0) {
            return false;
        }
        long chunkId = logical >>> PriceChunk.CHUNK_BITS;
        int offset = (int) (logical & PriceChunk.CHUNK_MASK);
        PriceChunk chunk = bs.directory.get(chunkId);
        if (chunk == null || !chunk.isLevelSet(offset)) {
            return false;
        }
        int slot = chunk.headOrder[offset];
        Side enumSide = bs.side;
        while (slot != OrderArena.NULL) {
            consumer.onOrder(arena.orderId[slot], enumSide,
                arena.priceTick[slot], arena.qty[slot]);
            slot = arena.nextOrder[slot];
        }
        return true;
    }

    // --- Misc ----------------------------------------------------------------

    @Override
    public int trim(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0; got " + depth);
        }
        return trimSide(bidSide, depth) + trimSide(askSide, depth);
    }

    private int trimSide(BookSide bs, int depth) {
        if (bs.depth() <= depth) {
            return 0;
        }
        // Two-pass: collect order slots in levels beyond `depth` into the
        // reusable scratch buffer, then drain. Direct in-place removal during
        // iteration would CME the TreeMap iterator when a chunk drops to zero
        // levels. Buffer grows on demand only — amortized allocation-free.
        int kept = 0;
        int nScratch = 0;
        Iterator<PriceChunk> chunkIt = bs.directory.iterator();
        while (chunkIt.hasNext()) {
            PriceChunk chunk = chunkIt.next();
            for (int offset = BitmapUtils.nextSetBit(chunk.levelBitmap, 0, PriceChunk.CHUNK_SIZE);
                 offset != BitmapUtils.NOT_FOUND;
                 offset = BitmapUtils.nextSetBit(chunk.levelBitmap, offset + 1, PriceChunk.CHUNK_SIZE)) {
                if (kept < depth) {
                    kept++;
                    continue;
                }
                int slot = chunk.headOrder[offset];
                while (slot != OrderArena.NULL) {
                    if (nScratch == trimScratch.length) {
                        trimScratch = Arrays.copyOf(trimScratch, nScratch * 2);
                    }
                    trimScratch[nScratch++] = slot;
                    slot = arena.nextOrder[slot];
                }
            }
        }
        for (int i = 0; i < nScratch; i++) {
            bs.removeOrder(arena, idIndex, trimScratch[i]);
        }
        return nScratch;
    }

    @Override
    public int depth(Side side) {
        return sideFor(side).depth();
    }

    @Override
    public int orderCount() {
        return idIndex.size();
    }

    private BookSide sideFor(Side side) {
        return side == Side.BID ? bidSide : askSide;
    }

    private BookSide sideForTag(byte tag) {
        return tag == (byte) 'B' ? bidSide : askSide;
    }

    private static Side sideOf(byte tag) {
        return tag == (byte) 'B' ? Side.BID : Side.ASK;
    }

    // --- Internal diagnostics ------------------------------------------------

    /**
     * Package-private invariant check for tests. Verifies the doc §9 invariants
     * over arena, bitmaps, FIFO links, and the best cache. Throws
     * {@link IllegalStateException} on the first violation.
     */
    void assertInternalInvariants() {
        InvariantHelper.check(this);
    }

    BookSide bidSideForTest() {
        return bidSide;
    }

    BookSide askSideForTest() {
        return askSide;
    }

    OrderArena arenaForTest() {
        return arena;
    }

    OrderIdIndex idIndexForTest() {
        return idIndex;
    }
}
