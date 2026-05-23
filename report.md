# Single-Instrument L3 Order Book Report

Report date: 2026-05-24

## 1. Summary

### Objective

This project implements a single-instrument Level-3 (L3) limit order book in Java, with emphasis on correctness, deterministic state transitions, and low-latency data-structure design.

The implemented book supports:

- Full L3 state: multiple individual orders per price level.
- Independent bid and ask sides.
- Order lifecycle operations: add, update, remove, and `update(qty = 0)` as removal.
- Automatic removal of empty price levels.
- Query by exact price and by level index.
- Best-to-worst level iteration and FIFO order iteration within a level.
- Book-depth trimming.
- Deterministic tests, randomized parity tests, generated datasets, and JMH replay benchmarks.

The implementation also supports price-time priority matching on `add`. If a dataset is purely post-only, the matching branch is naturally not exercised and the system behaves like passive L3 book maintenance.

### Code Structure

Repository: https://github.com/soyvv/zk_orderbook

```text
src/main/java/com/zzk/orderbook
    core/
        L3OrderBook.java                 public book contract
        PrecisionUtils.java              boundary conversion and validation helpers
        ref/
            TreeMapOrderBook.java        v0 reference implementation
        v1/
            ChunkedOrderBook.java        low-latency implementation
            BookSide.java                per-side ladder, best cache, FIFO operations
            PriceChunk.java              dense 4096-tick level block
            OrderArena.java              slot-based primitive order storage
            ArrayChunkDirectory.java     bounded O(1) chunk directory
            TreeMapChunkDirectory.java   sparse correctness-friendly chunk directory
            ChunkPool.java               reusable chunk pool
            AgronaLongLongIndex.java     primitive order-id index
    datagen/
        CsvDatasetGenerator.java         deterministic benchmark dataset generator
    model/
        Side, Order, Trade, PriceLevel, PrecisionSpec, mutable holders

src/test/java/com/zzk/orderbook
    deterministic unit tests
    randomized reference parity tests
    component tests for chunks, arena, bitmap, precision, directories

src/jmh/java/com/zzk/orderbook/perf
    BookReplayBenchmark.java             replay benchmark with validation
```

### Performance Summary

The reference implementation is intentionally simple and allocation-heavy. The optimized `v1-chunked-array` implementation is designed to keep steady-state mutation and hot queries close to allocation-free by using primitive arrays, bitmap-backed chunks, and a preallocated chunk pool.

From the full local JMH sweep summarized at `data/jmh/20260524_0006_1000k_p01_cross_off.txt`, `v1-chunked-array` wins on all 18 datasets. The optimized path runs at roughly 36 to 117 ns/event across the measured workloads, which corresponds to about 8.6M to 27.8M events/sec. The reference `TreeMap` implementation runs at roughly 192 to 912 ns/event, or about 1.1M to 5.2M events/sec.

At a high level:

- 1M-event datasets: `v1-chunked-array` is about 7.2x to 8.4x faster.
- 100k-event datasets: `v1-chunked-array` is about 6.5x to 9.2x faster.
- 10k-event datasets: `v1-chunked-array` is about 3.8x to 5.8x faster.

GC profiler data shows the reference implementation allocating hundreds of MB per replay, while the chunked-array implementation reports approximately 6.8 KB per replay and zero GC collections in the measured runs. That remaining allocation is benchmark/framework-level or first-touch overhead, not per-order object allocation in the hot mutation path.

### Build and Run

```bash
mvn test
mvn -DskipTests package
```

Run a single test class:

```bash
mvn test -Dtest=TreeMapOrderBookTest
```

Run benchmarks:

```bash
./run_perf.sh                   # full dataset sweep with default impls
./run_perf.sh -prof gc          # add JMH GC profiler output
./run_perf.sh -wi 2 -i 3 -f 1   # quick smoke run with fewer iterations
```

Select implementations and datasets:

```bash
IMPLS=treemap,v1-chunked,v1-chunked-array \
DATASETS=10k_p01_cross_off,1000k_p10_cross_off \
    ./run_perf.sh
```

## 2. Scope and Assumptions

### Instrument and Numeric Model

The book is single-instrument. Prices and quantities passed into the engine are already normalized integer counts of the instrument's price tick and quantity lot/step. The core book never parses decimals and never uses floating-point values.

For example, if the external price tick is `0.01`, a caller can represent `123.45` as `12345` before submitting the order. If the external quantity step is `0.001`, a caller can represent `2.500` as `2500`. The engine stores and compares those integer values directly.

`PrecisionSpec` carries:

- `priceScale`
- `priceTick`
- `qtyScale`
- `qtyStep`
- optional min/max and notional fields

`PrecisionUtils` provides the boundary helpers for converting back and forth:

- `PrecisionUtils.toScaledLong("123.45", priceScale)` converts a decimal string to the engine's integer representation.
- `PrecisionUtils.toBigDecimal(priceLong, priceScale)` converts an engine integer back to a display decimal.
- `PrecisionUtils.parse(priceTick, qtyStep)` derives a `PrecisionSpec` from exchange tick/lot strings.
- `PrecisionUtils.requirePriceAligned(...)`, `requireQtyAligned(...)`, and `requireBounds(...)` validate input before it reaches the hot book path.

The book itself does not call those checks on mutation because validation is assumed to happen at the caller or feed-handler boundary.

### Order Types

Implemented:

- Limit order add.
- Quantity update.
- Remove/cancel by order id.
- `update(orderId, 0)` as remove.

Out of scope:

- Market orders.
- IOC/FOK semantics.
- Multi-instrument routing.
- Persistence.
- Concurrency.

### Matching Semantics

The implemented `add` uses price-time priority:

- A bid crosses if best ask price is `<= bid price`.
- An ask crosses if best bid price is `>= ask price`.
- Matching always consumes the best opposite price first.
- Within a price level, matching consumes older orders before newer orders.
- Any residual quantity rests at the incoming order price.

For purely post-only generated datasets, no crossing trades are produced and this matching path has no behavioral impact on the final resting book except for the normal add/update/remove lifecycle.

### Threading

The book assumes single-threaded access. There is no internal synchronization. This is intentional for the latency-focused exercise.

## 3. Design and Analysis

### v0: Reference Design

`TreeMapOrderBook` is the correctness-first implementation.

Structure:

```text
TreeMapOrderBook
    bids: TreeMap<Long, MutablePriceLevel> with reverse price order
    asks: TreeMap<Long, MutablePriceLevel> with natural price order
    index: HashMap-like orderId -> OrderRef

MutablePriceLevel
    LinkedHashMap<Long, Order> preserving FIFO insertion order
```

Why this design is useful:

- `TreeMap` gives deterministic price ordering.
- `LinkedHashMap` preserves FIFO order within each price level.
- The global order index makes update/remove by id direct.
- The code is easy to reason about and serves as a reference model for randomized parity tests.

Main trade-offs:

- Tree nodes, map entries, order objects, and references produce allocation and GC pressure.
- `TreeMap` nodes are pointer-heavy and cache-unfriendly.
- `getByLevel(k)` requires walking from the best level to rank `k`.
- This design is excellent for correctness validation but not the final latency target.

### v1: Low-Latency Optimized Design

`ChunkedOrderBook` keeps the same public behavior but changes the internal representation.

High-level structure:

```text
ChunkedOrderBook
    bidSide: BookSide
    askSide: BookSide
    arena: OrderArena
    idIndex: OrderIdIndex

BookSide
    directory: ChunkDirectory
    cached best chunk id
    cached best offset
    depth count

ChunkDirectory
    TreeMapChunkDirectory      sparse, simpler
    ArrayChunkDirectory        bounded, bitmap-backed, low-latency

PriceChunk
    4096 price offsets
    arrays for total quantity, order count, FIFO head/tail
    bitmap for non-empty levels

OrderArena
    primitive arrays for order id, side, price, quantity, chunk, offset, prev, next
```

### Slot Map / Arena

`OrderArena` stores live orders in parallel primitive arrays. Each live order occupies one slot.

This design follows the slot-map style commonly used in low-latency systems: store objects in stable integer slots, keep dense primitive arrays for hot fields, and pass compact handles instead of object references. A useful background reference for this pattern is the slot-map discussion at https://www.youtube.com/watch?v=SHaAR7XPtNU&t=36.

Stored per slot:

- external order id
- side tag
- price
- quantity
- owning chunk id
- owning offset
- previous order slot at the same price level
- next order slot at the same price level
- generation counter

This avoids allocating an `Order` object on hot-path add/update/remove. The same slot links also make FIFO unlink for cancel/update paths straightforward.

The external order-id index stores `orderId -> arena handle`. A handle packs generation and slot index into one `long`.

### PriceChunk

`PriceChunk` is a dense block of 4096 price levels:

```text
CHUNK_SIZE = 4096
offset = logicalPriceIndex & 4095
chunkId = logicalPriceIndex >>> 12
```

Per offset, the chunk stores:

- total resting quantity
- order count
- FIFO head slot
- FIFO tail slot

A 64-word bitmap tracks which offsets are non-empty. The lowest set offset is cached as the best offset inside that chunk.

This design keeps level metadata compact and makes best-level discovery a bitmap operation rather than a full scan.

### PriceChunk Indexing

Both sides are transformed so lower logical index means better price.

Ask side:

```text
logicalIdx = priceTick - askOriginTick
priceTick = askOriginTick + logicalIdx
```

Bid side:

```text
logicalIdx = bidMaxTick - priceTick
priceTick = bidMaxTick - logicalIdx
```

After this transform:

- best ask is the lowest ask logical index,
- best bid is the lowest bid logical index,
- both sides can share the same chunk and bitmap traversal logic.

### Zero Allocation on Hot Path

The optimized array-backed path preallocates:

- order slots in `OrderArena`,
- chunk objects and chunk arrays in `ChunkPool`,
- directory arrays and active chunk bitmaps,
- primitive order-id index storage.

Steady-state mutation avoids per-order object allocation:

- add appends to primitive arrays and existing chunk arrays,
- update changes quantity in place,
- remove unlinks arena slots and returns them to the free list,
- empty chunks are returned to the pool.

The public convenience APIs intentionally allocate immutable snapshots. Hot callers should use:

- `getOrder(orderId, MutableOrder out)`
- `getByPrice(side, price, MutableLevel out)`
- `getByLevel(side, levelIndex, MutableLevel out)`
- `forEachLevel(side, LevelConsumer)`
- `forEachOrderAtPrice(side, price, OrderConsumer)`

### Complexity Analysis

Let:

- `N` = active orders.
- `P` = active price levels on one side.
- `C` = active chunks on one side.
- `K` = requested level index.
- `L` = number of levels requested by a top-K or range-level query.
- `F` = fills caused by a crossing add.
- `R` = orders removed by trim.

Reference `TreeMapOrderBook` (for design reference):

| Operation | Complexity |
| --- | --- |
| Passive add | O(log P) level lookup/create + O(1) average index insert |
| Crossing add | O(F + removed-level log P) |
| Update quantity | O(1) average index + O(log P) level lookup |
| Remove | O(1) average index + O(log P) if level is removed |
| Get by exact price | O(log P) |
| Get by level K | O(K) |
| Get top L levels | O(L) |
| Get level range from K, limit L | O(K + L) |
| Iterate levels/orders | O(P + N) |
| Trim depth | O(P + R) |

Chunked implementation with `TreeMapChunkDirectory`:

| Operation | Complexity |
| --- | --- |
| Passive add | O(log C) chunk lookup/create + O(1) append |
| Crossing add | O(F + removed-chunk log C) |
| Update quantity | O(1) average index + O(1) aggregate update |
| Remove | O(1) average index + O(log C) only if chunk is removed |
| Get by exact price | O(log C) |
| Get by level K | O(chunks traversed + set levels scanned to K) |
| Get top L levels | O(chunks touched + L) |
| Get level range from K, limit L | O(chunks traversed to K + set levels scanned to K + L) |
| Iterate levels/orders | O(C + P + N) |
| Trim depth | O(P beyond depth + R) |

Chunked implementation with `ArrayChunkDirectory` (Optimized):

| Operation | Complexity |
| --- | --- |
| Passive add | O(1) chunk lookup/create in configured range + O(1) append |
| Crossing add | O(F), with O(1) level/chunk updates in normal bounded range |
| Update quantity | O(1) average index + O(1) aggregate update |
| Remove | O(1) average index + O(1) unlink; best refresh may scan active chunk bitmap words |
| Get by exact price | O(1) in configured range |
| Get by level K | O(active chunk bitmap words traversed + set levels scanned to K) |
| Get top L levels | O(active chunk bitmap words touched + L) |
| Get level range from K, limit L | O(active chunk bitmap words traversed to K + set levels scanned to K + L) |
| Iterate levels/orders | O(active chunk bitmap words + P + N) |
| Trim depth | O(P beyond depth + R) |

For the requirement's "levels 0..4" style query, the important operation is top-K/range retrieval, not repeated single-level lookup. Calling `getByLevel(side, i)` for each `i = 0..L-1` repeats the walk from best every time and costs roughly O(L^2) in the reference structure and O(repeated chunk scans + L^2) in the chunked structure. A dedicated top-level API should walk once from best and stop after `L` emitted levels, giving O(L) for the reference book and O(active chunk bitmap words touched + L) for the optimized chunked-array book.

### Low-Latency Analysis

The optimized design improves latency through:

- primitive arrays instead of per-order objects,
- intrusive FIFO links instead of map-entry mutation at every level,
- bitmap-based level discovery,
- cached best chunk and best offset,
- O(1) bounded array directory for configured price ranges,
- preallocated chunk pool,
- mutable holder and visitor APIs for hot queries.

The remaining predictable costs are:

- order-id lookup in Agrona `Long2LongHashMap`,
- bitmap scans when refreshing best after removing the current best level,
- `getByLevel(k)` walking from best to rank `k`,
- first-time scratch-buffer growth during large trims if it exceeds previous high-water mark.

### Further Optimization Areas

The current `v1-chunked-array` implementation already removes the dominant allocation and pointer-chasing costs from the reference design. The next improvements should be driven by workload measurements rather than added preemptively.

Potential next steps:

- Avoid iterator allocation in chunk traversal. `forEachLevel`, `getByLevel`, and `trimSide` currently go through `ChunkDirectory.iterator()`. A primitive callback traversal or package-private bitmap walk for `ArrayChunkDirectory` would remove small iterator allocations and simplify hot scans.
- Pre-size trim scratch storage. `trimScratch` grows on demand. If trim is part of a latency-sensitive path, sizing it from `arenaCapacity` or a config value would make trim predictably allocation-free.
- Validate `OrderIdIndex` sizing under peak-active workloads. The Agrona map is initialized from arena capacity, but allocation profiling should confirm no mid-run rehash for high-watermark datasets.
- Make precision validation policy explicit at API boundaries. The hot book intentionally assumes normalized tick/lot integers; a checked wrapper could serve non-hot callers without adding validation cost to the engine core.

## 4. Performance Comparison

### JMH Setup

Benchmark:

```text
com.zzk.orderbook.perf.BookReplayBenchmark.replay
```

Mode:

```text
SingleShotTime
```

The benchmark:

1. Loads a zipped dataset from `src/test/resources/datasets`.
2. Reads `events.csv`, `trades.csv`, and `meta.txt`.
3. Replays once through the reference book to validate expected trades.
4. Replays once through the benchmark subject to validate behavior.
5. Resets the book before measurement iterations.
6. Measures full event-stream replay time.

The full local run used:

```text
warmupIterations=10
measurementIterations=20
forks=1
threads=1
```

The benchmark can be run with GC profiling:

```bash
./run_perf.sh -prof gc
```

### Timing Results

The latest full 18-dataset sweep compares `treemap` against the optimized `v1-chunked-array` implementation. All values are ns/event; lower is better.

| Dataset | Events | TreeMap | v1-chunked-array | Speedup |
| --- | ---: | ---: | ---: | ---: |
| `1000k_p01_cross_off` | 1M | 635.8 | 85.4 | 7.4x |
| `1000k_p01_cross_on` | 1M | 620.2 | 85.9 | 7.2x |
| `1000k_p10_cross_off` | 1M | 842.7 | 111.1 | 7.6x |
| `1000k_p10_cross_on` | 1M | 832.7 | 116.0 | 7.2x |
| `1000k_p20_cross_off` | 1M | 883.1 | 116.6 | 7.6x |
| `1000k_p20_cross_on` | 1M | 912.1 | 108.4 | 8.4x |
| `100k_p01_cross_off` | 100k | 405.2 | 43.9 | 9.2x |
| `100k_p01_cross_on` | 100k | 384.1 | 45.1 | 8.5x |
| `100k_p10_cross_off` | 100k | 422.3 | 49.7 | 8.5x |
| `100k_p10_cross_on` | 100k | 425.3 | 53.2 | 8.0x |
| `100k_p20_cross_off` | 100k | 408.4 | 57.8 | 7.1x |
| `100k_p20_cross_on` | 100k | 420.4 | 64.7 | 6.5x |
| `10k_p01_cross_off` | 10k | 194.4 | 36.4 | 5.3x |
| `10k_p01_cross_on` | 10k | 206.7 | 35.9 | 5.8x |
| `10k_p10_cross_off` | 10k | 201.7 | 41.5 | 4.9x |
| `10k_p10_cross_on` | 10k | 207.4 | 52.6 | 3.9x |
| `10k_p20_cross_off` | 10k | 192.0 | 50.8 | 3.8x |
| `10k_p20_cross_on` | 10k | 210.5 | 49.3 | 4.3x |

Across the full sweep, the optimized implementation is fastest on every dataset. The speedup is strongest on 100k workloads, where `v1-chunked-array` is 6.5x to 9.2x faster. On 1M workloads it is 7.2x to 8.4x faster, and on 10k workloads it is 3.8x to 5.8x faster.

### Allocation Results

Selected GC profiler results from `data/jmh/20260524_0006_1000k_p01_cross_off.txt`:

| Dataset | Impl | Allocated per replay | GC count |
| --- | --- | ---: | ---: |
| `1000k_p01_cross_off` | TreeMap | 265.7 MB | 52 |
| `1000k_p01_cross_off` | v1 chunked-array | 6.7 KB | 0 |
| `1000k_p10_cross_off` | TreeMap | 367.6 MB | 83 |
| `1000k_p10_cross_off` | v1 chunked-array | 6.8 KB | 0 |
| `1000k_p20_cross_off` | TreeMap | 381.3 MB | 89 |
| `1000k_p20_cross_off` | v1 chunked-array | 6.8 KB | 0 |

Allocation profile after the chunk lifecycle cleanup is unchanged: `v1-chunked-array` stays around 6.8 KB per replay across datasets, with zero measured GC collections. The new `isCleanForRelease()` check adds bitmap reads on the natural-empty hot path but avoids the previous broad chunk clearing writes. The lifecycle cost shifted from clearing roughly 80 KB of chunk arrays to reading a 64-word bitmap plus scalar state checks.

### Chunk Lifecycle Cleanup

The performance correction came from making chunk release intent explicit:

- `PriceChunk.reset(...)` was replaced by `resetForAcquire(...)` for the fast acquire path. It now performs only the minimal state writes needed when a clean chunk is reacquired.
- `PriceChunk.clearActiveLevelsForRelease()` handles dirty forced-release paths by walking active bitmap bits and clearing only active levels.
- `PriceChunk.isCleanForRelease()` verifies natural-empty releases by checking scalar counters and the 64-word bitmap.
- `ChunkPool.release(...)` was split into `releaseClean(...)` and `releaseClearing(...)`, with both sharing the same final free-stack push.
- `ArrayChunkDirectory.remove(chunkId)` routes natural empty chunks to `releaseClean(...)`.
- `ArrayChunkDirectory.reset()` routes forced flushes to `releaseClearing(...)`.

This makes each call site declare its lifecycle intent and prevents normal remove paths from paying full chunk clearing cost.

### Performance Interpretation

The optimized implementation is materially faster on replay workloads because it avoids the dominant costs in the reference design:

- `TreeMap` node traversal and mutation,
- `LinkedHashMap` entry allocation,
- object allocation for orders and refs,
- GC work caused by those allocations.

The speedup grows on larger datasets where the reference implementation's object graph and GC pressure accumulate. The `v1-chunked-array` path keeps mutation state in primitive arrays and bounded chunk pools, so replay time is mostly hash lookup, array mutation, bitmap update, and branch cost.

## 5. Test Methodology

### Test Strategy

The test strategy follows this sequence:

1. Build the reference design.
2. Build deterministic scenario tests against the reference.
3. Build the optimized implementation behind the same `L3OrderBook` contract.
4. Compare optimized behavior against the reference with randomized parity tests.
5. Generate deterministic benchmark datasets with the reference implementation as the trade oracle.
6. Validate benchmark subjects against dataset `trades.csv` before measuring.

### Unit and Component Tests

The suite covers:

- add/update/remove lifecycle,
- `qty = 0` removal,
- empty level cleanup,
- bid/ask sort order,
- FIFO order inside price levels,
- exact-price queries,
- level-index queries,
- trim behavior and idempotence,
- crossing match behavior,
- precision parsing and conversion,
- bitmap utilities,
- chunk pool behavior,
- chunk directory behavior,
- arena allocation/free behavior.

Current verification:

```text
mvn test
Tests run: 147, Failures: 0, Errors: 0, Skipped: 0
```

### Randomized Parity Tests

`RandomizedParityTest` drives 50,000 generated operations through:

- `TreeMapOrderBook` as reference,
- `ChunkedOrderBook` with `TreeMapChunkDirectory`,
- `ChunkedOrderBook` with `ArrayChunkDirectory`.

After each operation, the test compares:

- emitted trades,
- bid depth,
- ask depth,
- total order count,
- level snapshots,
- per-level FIFO order ids and quantities.

This catches most state drift bugs because the optimized implementation must agree with an independent data structure after every mutation.

### Benchmark Dataset Validation

`CsvDatasetGenerator` writes:

- `events.csv`,
- `trades.csv`,
- `meta.txt`.

The generator uses the reference implementation to produce `trades.csv`, so benchmark runs have a deterministic oracle.

`BookReplayBenchmark` validates both the reference and the benchmark subject against the expected trade stream before any measured iteration. This keeps benchmark measurements tied to correctness rather than measuring an unchecked fast path.

### Invariant Checking

The optimized implementation includes an internal invariant helper for tests. It checks:

- arena live count vs id-index size,
- per-side depth counts,
- chunk non-empty counts,
- bitmap consistency,
- cached best chunk and offset,
- FIFO link integrity,
- aggregate quantity and order count consistency.

There is also a generic `InvariantChecker` test utility placeholder. It should either be completed for all `L3OrderBook` implementations or removed to avoid stale placeholders in the test tree.

## Conclusion

The project satisfies the core L3 book requirements and adds a well-tested low-latency implementation. The reference implementation is simple and useful as an oracle. The optimized implementation improves throughput and allocation behavior by replacing object-heavy ordered maps with primitive arenas, chunked price ladders, bitmaps, and pooled chunks.
