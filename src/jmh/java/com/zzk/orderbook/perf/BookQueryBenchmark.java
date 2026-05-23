package com.zzk.orderbook.perf;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.core.ref.TreeMapOrderBook;
import com.zzk.orderbook.core.v1.ChunkedBookConfig;
import com.zzk.orderbook.core.v1.ChunkedOrderBook;
import com.zzk.orderbook.model.LevelConsumer;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.TradeListener;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Measures top-N level query latency on a populated book. Complements
 * {@link BookReplayBenchmark} (which measures mutation throughput): trial
 * setup replays the full event stream into the subject once to reach
 * steady state, then every measurement invocation runs two ranged
 * {@code forEachLevel(side, 0, topN, consumer)} calls — one per side — to
 * model an L5 / L10 market-data snapshot.
 *
 * <p>Configured via:
 * <ul>
 *   <li>{@code -p impl=treemap,v1-chunked-array}</li>
 *   <li>{@code -p dataset=10k_p10_cross_off}</li>
 *   <li>{@code -p topN=5,10}</li>
 * </ul>
 *
 * <p>{@link #main(String[])} writes a per-run summary to
 * {@code data/jmh/<yyyyMMdd_HHmm>_<dataset>_query.txt}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class BookQueryBenchmark {

    private static final long PRICE_MARGIN_TICKS = 256L;
    private static final int ARENA_MARGIN = 64;

    @Param({"10k_p10_cross_off"})
    public String dataset;

    @Param({"treemap", "v1-chunked-array"})
    public String impl;

    @Param({"5", "10"})
    public int topN;

    private L3OrderBook book;
    private SumConsumer consumer;

    @Setup(Level.Trial)
    public void buildPopulatedBook() throws IOException {
        Map<String, List<String>> entries = DataSetUtils.loadDatasetEntries(dataset);
        Map<String, String> meta = DataSetUtils.readMeta(entries.getOrDefault("meta.txt", List.of()));
        PrecisionSpec spec = DataSetUtils.precisionSpecFromMeta(meta);
        DataSetUtils.EventStream events = DataSetUtils.loadEvents(entries.get("events.csv"));

        // Pre-scan price range + peak active count via a reference replay so we
        // can size ChunkedBookConfig.array correctly (same approach as
        // BookReplayBenchmark.referenceReplay).
        Stats stats = referenceStats(events, spec);

        book = newBook(spec, stats);

        // Replay events once to reach steady-state book state.
        replayInto(book, events);

        consumer = new SumConsumer();

        System.out.printf(
            "[setup] dataset=%s impl=%s topN=%d events=%d bidDepth=%d askDepth=%d orderCount=%d%n",
            dataset, impl, topN, events.n(),
            book.depth(Side.BID), book.depth(Side.ASK), book.orderCount());
    }

    @Benchmark
    public long topNBothSides() {
        SumConsumer c = consumer;
        c.sum = 0L;
        book.forEachLevel(Side.BID, 0, topN, c);
        book.forEachLevel(Side.ASK, 0, topN, c);
        return c.sum;
    }

    // --- factory + replay helpers -------------------------------------------

    private L3OrderBook newBook(PrecisionSpec spec, Stats stats) {
        return switch (impl) {
            case "treemap" -> new TreeMapOrderBook(spec);
            case "v1-chunked" -> new ChunkedOrderBook(ChunkedBookConfig.treeMap(
                spec,
                Math.max(0L, stats.minPrice - PRICE_MARGIN_TICKS),
                stats.maxPrice + PRICE_MARGIN_TICKS,
                stats.peakOrderCount + ARENA_MARGIN));
            case "v1-chunked-array" -> {
                long askOrigin = Math.max(0L, stats.minPrice - PRICE_MARGIN_TICKS);
                long bidMax = stats.maxPrice + PRICE_MARGIN_TICKS;
                int chunkRange = (int) (((bidMax - askOrigin) >>> 12) + 2);
                yield new ChunkedOrderBook(ChunkedBookConfig.array(
                    spec, askOrigin, bidMax,
                    stats.peakOrderCount + ARENA_MARGIN,
                    0, chunkRange - 1, chunkRange));
            }
            default -> throw new IllegalArgumentException("unknown impl: " + impl);
        };
    }

    /** Replay events through a reference book solely to derive sizing stats. */
    private static Stats referenceStats(DataSetUtils.EventStream events, PrecisionSpec spec) {
        L3OrderBook ref = new TreeMapOrderBook(spec);
        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;
        int peak = 0;
        TradeListener discard = TradeListener.DISCARD;
        for (int i = 0; i < events.n(); i++) {
            byte op = events.ops()[i];
            if (op == 'A') {
                long p = events.prices()[i];
                if (p < minPrice) minPrice = p;
                if (p > maxPrice) maxPrice = p;
                ref.add(events.orderIds()[i],
                    events.sides()[i] == 'B' ? Side.BID : Side.ASK,
                    p, events.qtys()[i], discard);
            } else if (op == 'U') {
                ref.update(events.orderIds()[i], events.qtys()[i]);
            } else {
                ref.remove(events.orderIds()[i]);
            }
            if (ref.orderCount() > peak) peak = ref.orderCount();
        }
        return new Stats(minPrice, maxPrice, peak);
    }

    private static void replayInto(L3OrderBook book, DataSetUtils.EventStream events) {
        TradeListener discard = TradeListener.DISCARD;
        byte[] ops = events.ops();
        byte[] sides = events.sides();
        long[] orderIds = events.orderIds();
        long[] prices = events.prices();
        long[] qtys = events.qtys();
        int n = events.n();
        for (int i = 0; i < n; i++) {
            byte op = ops[i];
            if (op == 'A') {
                book.add(orderIds[i],
                    sides[i] == 'B' ? Side.BID : Side.ASK,
                    prices[i], qtys[i], discard);
            } else if (op == 'U') {
                book.update(orderIds[i], qtys[i]);
            } else {
                book.remove(orderIds[i]);
            }
        }
    }

    private record Stats(long minPrice, long maxPrice, int peakOrderCount) {}

    /**
     * Mixes (price, orderCount, totalQuantity) into a {@code long} sink so JMH
     * can't dead-code-eliminate the calls. XOR over arithmetic so the JIT can't
     * reduce the loop to a closed form when {@code topN} is param-constant.
     */
    private static final class SumConsumer implements LevelConsumer {
        long sum;

        @Override
        public void onLevel(Side side, long price, int orderCount, long totalQuantity) {
            sum ^= price;
            sum ^= orderCount;
            sum ^= totalQuantity;
        }
    }

    // --- JMH entry point + per-run summary ----------------------------------

    public static void main(String[] args) throws Exception {
        Options opts = new OptionsBuilder()
            .parent(new CommandLineOptions(args))
            .include(BookQueryBenchmark.class.getSimpleName())
            .build();
        Collection<RunResult> results = new Runner(opts).run();
        writeSummary(results);
    }

    private static void writeSummary(Collection<RunResult> results) throws IOException {
        if (results.isEmpty()) {
            System.err.println("[summary] no results to write");
            return;
        }

        RunResult first = results.iterator().next();
        Set<String> datasets = new LinkedHashSet<>();
        for (RunResult r : results) {
            datasets.add(r.getParams().getParam("dataset"));
        }
        String firstDataset = first.getParams().getParam("dataset");
        LocalDateTime now = LocalDateTime.now();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(now);

        Path outDir = Path.of("data", "jmh");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(stamp + "_" + firstDataset + "_query.txt");

        StringBuilder sb = new StringBuilder();
        sb.append("# BookQueryBenchmark run\n");
        sb.append("timestamp=").append(now).append('\n');
        sb.append("dataset=").append(datasets).append('\n');

        sb.append('\n').append("## jmh config\n");
        sb.append("benchmark=").append(first.getParams().getBenchmark()).append('\n');
        sb.append("mode=").append(first.getParams().getMode()).append('\n');
        sb.append("warmupIterations=").append(first.getParams().getWarmup().getCount()).append('\n');
        sb.append("measurementIterations=")
            .append(first.getParams().getMeasurement().getCount()).append('\n');
        sb.append("forks=").append(first.getParams().getForks()).append('\n');
        sb.append("threads=").append(first.getParams().getThreads()).append('\n');

        sb.append('\n').append("## results (ns/op = both sides per invocation)\n");
        sb.append(String.format("%-18s %-22s %6s %14s %s%n",
            "impl", "dataset", "topN", "ns/op", "error"));
        for (RunResult r : results) {
            Result<?> primary = r.getPrimaryResult();
            String ds = r.getParams().getParam("dataset");
            String topN = r.getParams().getParam("topN");
            double nsOp = DataSetUtils.nanosPerReplay(primary.getScore(), primary.getScoreUnit());
            double error = Double.isNaN(primary.getScoreError()) ? 0.0
                : DataSetUtils.nanosPerReplay(primary.getScoreError(), primary.getScoreUnit());
            sb.append(String.format("%-18s %-22s %6s %14.3f %.3f%n",
                r.getParams().getParam("impl"),
                ds,
                topN,
                nsOp,
                error));
        }

        boolean hasSecondaryResults = results.stream()
            .anyMatch(r -> !r.getSecondaryResults().isEmpty());
        if (hasSecondaryResults) {
            sb.append('\n').append("## secondary results\n");
            sb.append(String.format("%-14s %-22s %6s %-32s %14s %14s %s%n",
                "impl", "dataset", "topN", "metric", "score", "error", "units"));
            for (RunResult r : results) {
                String impl = r.getParams().getParam("impl");
                String ds = r.getParams().getParam("dataset");
                String topN = r.getParams().getParam("topN");
                for (Result<?> secondary : r.getSecondaryResults().values()) {
                    sb.append(String.format("%-14s %-22s %6s %-32s %14.3f %14.3f %s%n",
                        impl, ds, topN,
                        secondary.getLabel(),
                        secondary.getScore(),
                        Double.isNaN(secondary.getScoreError()) ? 0.0 : secondary.getScoreError(),
                        secondary.getScoreUnit()));
                }
            }
        }

        Files.writeString(outFile, sb.toString());
        System.out.println("[summary] wrote " + outFile.toAbsolutePath());
    }
}
