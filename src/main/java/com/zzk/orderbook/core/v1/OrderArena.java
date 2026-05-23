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

    final long[] orderId;
    final long[] priceTick;
    final long[] qty;
    final long[] chunkId;
    final int[] offset;
    final int[] prevOrder;
    final int[] nextOrder;
    final byte[] side;
    final int[] generation;

    private int freeHead;
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
