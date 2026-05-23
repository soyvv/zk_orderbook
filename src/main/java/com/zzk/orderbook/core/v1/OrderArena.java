package com.zzk.orderbook.core.v1;

/**
 * Primitive-array order arena with intrusive FIFO links and generation-protected
 * handles. Single-threaded.
 *
 * <p>Layout: parallel arrays sized at construction. Free slots are linked through
 * {@code nextOrder} starting at {@link #freeHead}. Live slots store the order's
 * full state plus {@code chunkId}/{@code offset} (owning level) and FIFO
 * {@code prevOrder}/{@code nextOrder} pointers.
 */
final class OrderArena {

    static final int NULL = -1;

    private final int capacity;

    /** External order id supplied by the caller (e.g. exchange-assigned id). */
    final long[] orderId;
    /** Resting price in scaled-long form (same scale as {@link com.zzk.orderbook.model.PrecisionSpec#priceTick()}). */
    final long[] priceTick;
    /** Remaining quantity in scaled-long form. Mutated in place on partial fills and updates. */
    final long[] qty;
    /** Owning chunk on this side's ladder; lets the book find the level without walking the directory. */
    final long[] chunkId;
    /** Owning offset within the chunk (0..{@link PriceChunk#CHUNK_SIZE}-1). */
    final int[] offset;
    /** Intrusive FIFO: previous slot at the same price level, or {@link #NULL} if head. */
    final int[] prevOrder;
    /** Intrusive FIFO: next slot at the same price level, or {@link #NULL} if tail.
     *  Doubles as the free-list link while a slot is unallocated. */
    final int[] nextOrder;
    /** Side tag of the resting order: {@code 'B'} or {@code 'A'}, matching {@link BookSide#sideTag}. */
    final byte[] side;
    /**
     * Slot reuse counter. Incremented each time the slot is freed; combined
     * with the slot index into a packed handle so a stale handle (one that
     * pointed at the slot before reuse) is detectable: see {@link #handleOf}
     * and {@link #isLive}.
     */
    final int[] generation;

    /** Head of the singly-linked free-list threaded through {@link #nextOrder}; {@link #NULL} when full. */
    private int freeHead;
    /** Number of currently-allocated slots; mirror of "non-free count" for cheap O(1) {@link #liveCount()}. */
    private int liveCount;

    OrderArena(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0; got " + capacity);
        }
        this.capacity = capacity;
        this.orderId = new long[capacity];
        this.priceTick = new long[capacity];
        this.qty = new long[capacity];
        this.chunkId = new long[capacity];
        this.offset = new int[capacity];
        this.prevOrder = new int[capacity];
        this.nextOrder = new int[capacity];
        this.side = new byte[capacity];
        this.generation = new int[capacity];

        // Build the free-list: 0 -> 1 -> 2 -> ... -> capacity-1 -> NULL.
        for (int i = 0; i < capacity - 1; i++) {
            nextOrder[i] = i + 1;
        }
        nextOrder[capacity - 1] = NULL;
        freeHead = 0;
    }

    int capacity() {
        return capacity;
    }

    int liveCount() {
        return liveCount;
    }

    /**
     * Rebuild the free-list in place and invalidate every existing handle by
     * bumping generations. Allocation-free; reuses the existing arrays.
     */
    void reset() {
        for (int i = 0; i < capacity; i++) {
            generation[i]++;
        }
        for (int i = 0; i < capacity - 1; i++) {
            nextOrder[i] = i + 1;
        }
        nextOrder[capacity - 1] = NULL;
        freeHead = 0;
        liveCount = 0;
    }

    int allocate(long orderId, byte side, long priceTick, long qty, long chunkId, int offset) {
        if (freeHead == NULL) {
            throw new IllegalStateException("arena full (capacity=" + capacity + ")");
        }
        int slot = freeHead;
        freeHead = nextOrder[slot];

        this.orderId[slot] = orderId;
        this.side[slot] = side;
        this.priceTick[slot] = priceTick;
        this.qty[slot] = qty;
        this.chunkId[slot] = chunkId;
        this.offset[slot] = offset;
        this.prevOrder[slot] = NULL;
        this.nextOrder[slot] = NULL;
        liveCount++;
        return slot;
    }

    void free(int slot) {
        generation[slot]++;
        nextOrder[slot] = freeHead;
        prevOrder[slot] = NULL;
        freeHead = slot;
        liveCount--;
    }

    /**
     * Pack the slot's current generation + slot index into a single
     * {@code long} handle. Layout: high 32 bits = generation, low 32 bits =
     * slot index. The packed value is what's stored in the
     * {@link OrderIdIndex} so a single long-keyed lookup yields both the
     * arena coordinates and the staleness check ({@link #isLive}).
     */
    long handleOf(int slot) {
        return ((long) generation[slot] << 32) | (slot & 0xffffffffL);
    }

    static int slotOf(long handle) {
        return (int) (handle & 0xffffffffL);
    }

    static int generationOf(long handle) {
        return (int) (handle >>> 32);
    }

    boolean isLive(long handle) {
        int slot = slotOf(handle);
        return slot >= 0 && slot < capacity && generation[slot] == generationOf(handle);
    }
}
