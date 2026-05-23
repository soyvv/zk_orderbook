# orderbook

Single-instrument L3 limit order book in Java. See [requirements.md](requirements.md) and [analysis.md](analysis.md).

## Layout

```
src/main/java/com/zzk/orderbook/
    model/       Order, Side, PriceLevel, BookSnapshotLevel, PrecisionSpec, Trade,
                 TradeListener, MutableOrder, MutableLevel, LevelConsumer, OrderConsumer
    core/        L3OrderBook (interface)
    core/impl/   TreeMapOrderBook (reference impl) + OrderIndex/HashMapOrderIndex
    core/v1/     ChunkedOrderBook + ChunkedBookConfig + ChunkPool + ArrayChunkDirectory
                 + TreeMapChunkDirectory + OrderArena + PriceChunk + AgronaLongLongIndex
    precision/   PrecisionParser, PriceQtyConverter, PrecisionValidator (stubs)
    perf/        CsvDatasetGenerator
src/test/java/com/zzk/orderbook/
    core/             RandomizedParityTest (cross-impl, ref vs each v1 variant)
    core/impl/        TreeMapOrderBookTest, MatchingTest
    core/v1/          ChunkedOrderBookTest, ChunkedMatchingTest, ChunkPoolTest,
                      PriceChunkTest, ArrayChunkDirectoryTest, ChunkDirectoryTest,
                      OrderArenaTest, ChunkedOrderBookHotApiTest
    invariant/        InvariantChecker
src/test/resources/datasets/
    *.zip             benchmark fixtures, each zip = {events.csv, trades.csv, meta.txt}
src/jmh/java/com/zzk/orderbook/perf/
    BookReplayBenchmark
```

## Build

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

```
mvn -q -DskipTests test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "$(cat cp.txt):target/test-classes:target/classes" \
     com.zzk.orderbook.perf.BookReplayBenchmark \
     -p impl=treemap,v1-chunked,v1-chunked-array \
     -p dataset=10k_p01_cross_off,1000k_p10_cross_off
```

`-p impl=...` selects which implementation(s) to benchmark; all replay the
same events and emit comparable numbers. `-p dataset=...` names one or more
zips under `datasets/` (e.g. `10k_p20_cross_on`). Pass any standard JMH
options on the same command line (e.g. `-wi 2 -i 3 -f 1` for a quick smoke
run, `-prof gc` for allocation profiling).

To add a new dataset: generate it with `CsvDatasetGenerator` (see
[Dataset Generation](#dataset-generation)), `zip` the three output files
into `src/test/resources/datasets/<name>.zip`, then re-run with
`-p dataset=<name>`.

## Dataset Generation

Build first:

```
mvn -DskipTests package
```

Generate event and trade CSVs using the reference `TreeMapOrderBook`:

```
java -cp target/classes com.zzk.orderbook.perf.CsvDatasetGenerator \
  --profile small \
  --dist clustered \
  --seed 42 \
  --add-rate 0.50 \
  --update-rate 0.30 \
  --remove-rate 0.20 \
  --events-out data/generated/events.csv \
  --trades-out data/generated/trades.csv \
  --meta-out data/generated/meta.txt
```

By default, adds are passive and the trades CSV only contains its header. Add
`--allow-crossing` to generate aggressive adds and trade rows from the reference
matching logic.

For a stream concentrated around top of book:

```
java -cp target/classes com.zzk.orderbook.perf.CsvDatasetGenerator \
  --profile small \
  --dist clustered \
  --seed 42 \
  --add-rate 0.90 \
  --update-rate 0.30 \
  --remove-rate 0.20 \
  --price-anchor bbo \
  --active-selection bbo \
  --bbo-width-ticks 10 \
  --bbo-select-width-ticks 50 \
  --allow-crossing \
  --cross-rate 0.12 \
  --improve-quote-rate 0.25 \
  --events-out data/generated/events.csv \
  --trades-out data/generated/trades.csv \
  --meta-out data/generated/meta.txt
```

`--price-anchor bbo` places new orders around the current best bid/offer.
`--active-selection bbo` biases updates and cancels toward active orders near
top of book.

## Milestones

See [analysis.md §11](analysis.md). Current status: `TreeMapOrderBook` has the
reference book/matching path and focused tests; precision utilities and parity
tests still need implementation.
