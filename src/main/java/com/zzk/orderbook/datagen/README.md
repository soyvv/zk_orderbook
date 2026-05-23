# `com.zzk.orderbook.datagen` — offline dataset generation

`CsvDatasetGenerator` produces deterministic event streams used by
`BookReplayBenchmark` (see [src/jmh/java/.../BookReplayBenchmark.java](../../../../../../jmh/java/com/zzk/orderbook/perf/BookReplayBenchmark.java)).
The generator replays candidate operations against the reference
`TreeMapOrderBook` so the recorded `trades.csv` is the exact ground truth
the matching engine produces; benchmark replays then compare each
implementation's trade output against this file.

Outputs three files per run:
- `events.csv` — header `seq,op,side,order_id,price,qty`; `op ∈ {A,U,R}`,
  `side ∈ {B,A,""}`
- `trades.csv` — header `seq,maker_order_id,taker_order_id,taker_side,price,qty`
- `meta.txt` — `key=value` pairs capturing the generator config (used by
  `BookReplayBenchmark` to seed `PrecisionSpec` and to dedupe runs)

## Usage

Build once:

```
mvn -DskipTests package
```

Generate a small clustered passive-only stream:

```
java -cp target/classes com.zzk.orderbook.datagen.CsvDatasetGenerator \
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

By default, adds are passive and the trades CSV only contains its header.
Add `--allow-crossing` to generate aggressive adds and trade rows from the
reference matching logic.

A stream concentrated around top-of-book with crossings:

```
java -cp target/classes com.zzk.orderbook.datagen.CsvDatasetGenerator \
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
`--active-selection bbo` biases updates and cancels toward active orders
near top of book.

## Packaging a dataset for the benchmark

`BookReplayBenchmark` loads datasets as classpath resources at
`/datasets/<name>.zip`. After generating the three files, bundle them:

```
cd data/generated
zip -9 ../../src/test/resources/datasets/<name>.zip \
    events.csv trades.csv meta.txt
```

Then run the benchmark with `-p dataset=<name>`.
