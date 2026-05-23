# orderbook

Single-instrument L3 limit order book in Java 21.

A from-scratch exploration of low-latency book design: maintain full per-order
state (price, quantity, FIFO position) under add / update / cancel / match
traffic, expose price-time-priority matching, and serve top-of-book and
top-N depth queries. Two implementations live side-by-side and are validated
against each other:

- **`TreeMapOrderBook`** — reference impl. A `TreeMap<Long, MutablePriceLevel>`
  per side, deque per level. Compact and correct; the parity oracle for the
  optimized impl and the baseline for benchmark comparisons.
- **`ChunkedOrderBook` (v1)** — optimized impl. A sparse-ladder directory of
  4096-level chunks backed by primitive arrays, an order arena with intrusive
  FIFO links and generation-protected handles, and a cached best-chunk /
  best-offset pointer for O(1) top-of-book. The hot path is allocation-free
  and the public query API uses holder + visitor patterns
  (`MutableLevel`, `LevelConsumer`, `OrderConsumer`) so callers never force
  the book to materialize objects.

A full JMH sweep across 18 generated datasets (10k–1M events × 1/10/20%
price-range × cross-off/cross-on) shows v1-chunked-array running ~7–9× faster
than the TreeMap reference on the replay benchmark and ~2–3× faster on top-5
queries. See [report.md](report.md) for numbers and design rationale.

## Build

Requires JDK 21 and Maven.

```
mvn test                              # unit + parity tests
mvn -DskipTests package               # build jar
mvn test -Dtest=TreeMapOrderBookTest  # single test class
```

## Benchmarks

JMH sources live under `src/jmh/java` and are wired in as a test source root via
`build-helper-maven-plugin`.

`BookReplayBenchmark` replays a CSV event stream against any
`L3OrderBook` implementation and validates the resulting trade stream against
`trades.csv` at trial setup (outside the measured hot path). Both the
reference and the benchmark subject are validated before any iteration runs.

Datasets are packaged as zip files under
[src/test/resources/datasets/](src/test/resources/datasets/) — each zip
contains `events.csv`, `trades.csv`, and `meta.txt`. The benchmark loads
them as classpath resources, so no filesystem setup is required.

Use the wrapper script (defaults to every dataset, reference vs optimized
impls):

```
./run_perf.sh                   # full sweep: treemap vs v1-chunked-array
./run_perf.sh -prof gc          # add the GC profiler
./run_perf.sh -wi 2 -i 3 -f 1   # quick smoke run (forwarded JMH flags)
```

Overrides via env vars:

```
IMPLS=treemap,v1-chunked,v1-chunked-array \
DATASETS=10k_p01_cross_off,1000k_p10_cross_off \
    ./run_perf.sh
```

To add a new dataset: generate it with `CsvDatasetGenerator` (see
[`src/main/java/com/zzk/orderbook/datagen/README.md`](src/main/java/com/zzk/orderbook/datagen/README.md)
for the generator CLI), `zip` the three output files into
`src/test/resources/datasets/<name>.zip`, then re-run.

## Report

See [report.md](report.md) for the design write-up — design goals and non-goals, data structure choices and
trade-offs, complexity analysis, testing approach and further optimization directions.
