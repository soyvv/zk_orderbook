# orderbook

Single-instrument L3 limit order book in Java.

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

See [report.md](report.md) for the design write-up — reference vs optimized
implementation, the chunked-ladder data structure, allocation analysis, and
benchmark results across the 18-dataset sweep.
