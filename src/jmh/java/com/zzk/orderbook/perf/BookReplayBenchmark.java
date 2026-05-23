package com.zzk.orderbook.perf;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.core.ref.TreeMapOrderBook;
import com.zzk.orderbook.core.v1.ChunkedBookConfig;
import com.zzk.orderbook.core.v1.ChunkedOrderBook;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Replays a CSV event stream against any {@link L3OrderBook} implementation
 * and reports per-replay wall time. The dataset is a zip file packaged as a
 * classpath resource at {@code /datasets/<name>.zip} containing
 * {@code events.csv}, {@code trades.csv}, and an optional {@code meta.txt}.
 *
 * <p>Configured via:
 * <ul>
 *   <li>{@code -p impl=treemap,v1-chunked,v1-chunked-array} — which impls to compare</li>
 *   <li>{@code -p dataset=10k_p01_cross_off} — dataset name (zip basename)</li>
 * </ul>
 *
 * <p>After the JMH run, {@link #main(String[])} writes a summary file under
 * {@code data/jmh/} named {@code <yyyyMMdd_HHmm>_<dataset>.txt} containing the
 * dataset meta, JMH config, and per-impl scores.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10)
@Measurement(iterations = 20)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class BookReplayBenchmark {

    private static final long PRICE_MARGIN_TICKS = 256L;
    private static final int ARENA_MARGIN = 64;
    private static final String DATASET_RESOURCE_PREFIX = "/datasets/";

    @Param({"10k_p01_cross_off"})
    public String dataset;

    @Param({"treemap", "v1-chunked", "v1-chunked-array"})
    public String impl;

    // Event stream in primitive form.
    private byte[] ops;
    private byte[] sides;
    private long[] orderIds;
    private long[] prices;
    private long[] qtys;
    private int n;

    // Derived from a reference replay during trial setup.
    private long derivedMinPrice;
    private long derivedMaxPrice;
    private int derivedPeakOrderCount;
    private PrecisionSpec spec;

    private L3OrderBook book;

    @Setup(Level.Trial)
    public void loadAndValidate() throws IOException {
        Map<String, List<String>> entries = loadDatasetEntries(dataset);
        Map<String, String> meta = readMeta(entries.getOrDefault("meta.txt", List.of()));
        spec = precisionSpecFromMeta(meta);

        loadEvents(entries.get("events.csv"));
        List<Trade> expected = loadExpectedTrades(entries.get("trades.csv"));

        // Pass 1: TreeMap reference replay — validates expected trades AND
        // derives chunked-config inputs (price band + peak live orders).
        referenceReplay(expected);

        // Construct the subject ONCE per trial. Iteration-level setup just
        // resets it so any allocation that would have happened in freshBook()
        // is excluded from per-invocation alloc metrics under SingleShotTime.
        book = newBook();

        // Validate the subject (against the recorded trades.csv) by replaying
        // events through it, then reset before benchmarking starts.
        validateOnce(book, expected);
        book.reset();

        System.out.printf(
            "[setup] dataset=%s impl=%s events=%d trades=%d priceRange=[%d,%d] peakActive=%d%n",
            dataset, impl, n, expected.size(),
            derivedMinPrice, derivedMaxPrice, derivedPeakOrderCount);
    }

    @Setup(Level.Iteration)
    public void resetBook() {
        // Allocation-free for array-backed impls; clears tree-backed impls'
        // maps (next puts will re-allocate Entry — that's inherent to TreeMap).
        book.reset();
    }

    @Benchmark
    public L3OrderBook replay() {
        L3OrderBook b = book;
        TradeListener discard = TradeListener.DISCARD;
        for (int i = 0; i < n; i++) {
            byte op = ops[i];
            if (op == 'A') {
                b.add(orderIds[i],
                    sides[i] == 'B' ? Side.BID : Side.ASK,
                    prices[i], qtys[i], discard);
            } else if (op == 'U') {
                b.update(orderIds[i], qtys[i]);
            } else {
                b.remove(orderIds[i]);
            }
        }
        return b;
    }

    // --- factory & helpers ---------------------------------------------------

    private L3OrderBook newBook() {
        return switch (impl) {
            case "treemap" -> new TreeMapOrderBook(spec);
            case "v1-chunked" -> new ChunkedOrderBook(ChunkedBookConfig.treeMap(
                spec,
                Math.max(0L, derivedMinPrice - PRICE_MARGIN_TICKS),
                derivedMaxPrice + PRICE_MARGIN_TICKS,
                derivedPeakOrderCount + ARENA_MARGIN));
            case "v1-chunked-array" -> {
                long askOrigin = Math.max(0L, derivedMinPrice - PRICE_MARGIN_TICKS);
                long bidMax = derivedMaxPrice + PRICE_MARGIN_TICKS;
                // Both sides use logical-index range = bidMax - askOrigin ticks. Convert
                // to chunk range and add slack so a single dataset always fits.
                int chunkRange = (int) (((bidMax - askOrigin) >>> 12) + 2);
                yield new ChunkedOrderBook(ChunkedBookConfig.array(
                    spec, askOrigin, bidMax,
                    derivedPeakOrderCount + ARENA_MARGIN,
                    /* minChunkId = */ 0,
                    /* maxChunkId = */ chunkRange - 1,
                    /* chunkPoolCapacity = */ chunkRange));
            }
            default -> throw new IllegalArgumentException("unknown impl: " + impl);
        };
    }

    private void referenceReplay(List<Trade> expected) {
        L3OrderBook ref = new TreeMapOrderBook(spec);
        List<Trade> actual = new ArrayList<>(expected.size());
        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;
        int peak = 0;
        for (int i = 0; i < n; i++) {
            byte op = ops[i];
            if (op == 'A') {
                if (prices[i] < minPrice) minPrice = prices[i];
                if (prices[i] > maxPrice) maxPrice = prices[i];
                actual.addAll(ref.add(orderIds[i],
                    sides[i] == 'B' ? Side.BID : Side.ASK, prices[i], qtys[i]));
            } else if (op == 'U') {
                ref.update(orderIds[i], qtys[i]);
            } else {
                ref.remove(orderIds[i]);
            }
            if (ref.orderCount() > peak) peak = ref.orderCount();
        }
        assertTradesEqual(expected, actual, "treemap reference");
        derivedMinPrice = minPrice;
        derivedMaxPrice = maxPrice;
        derivedPeakOrderCount = peak;
    }

    /** Replay events through the given book and assert trades match expected. */
    private void validateOnce(L3OrderBook subject, List<Trade> expected) {
        List<Trade> actual = new ArrayList<>(expected.size());
        for (int i = 0; i < n; i++) {
            byte op = ops[i];
            if (op == 'A') {
                actual.addAll(subject.add(orderIds[i],
                    sides[i] == 'B' ? Side.BID : Side.ASK, prices[i], qtys[i]));
            } else if (op == 'U') {
                subject.update(orderIds[i], qtys[i]);
            } else {
                subject.remove(orderIds[i]);
            }
        }
        assertTradesEqual(expected, actual, impl);
    }

    private static void assertTradesEqual(List<Trade> expected, List<Trade> actual, String label) {
        if (actual.size() != expected.size()) {
            throw new IllegalStateException(label + ": trade count expected="
                + expected.size() + " actual=" + actual.size());
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).equals(expected.get(i))) {
                throw new IllegalStateException(label + ": trade mismatch at index " + i
                    + " expected=" + expected.get(i) + " actual=" + actual.get(i));
            }
        }
    }

    private void loadEvents(List<String> lines) {
        if (lines == null) {
            throw new IllegalStateException("dataset missing events.csv entry");
        }
        int count = lines.size() - 1;
        ops = new byte[count];
        sides = new byte[count];
        orderIds = new long[count];
        prices = new long[count];
        qtys = new long[count];
        for (int i = 0; i < count; i++) {
            String[] parts = lines.get(i + 1).split(",", -1);
            ops[i] = (byte) parts[1].charAt(0);
            sides[i] = parts[2].isEmpty() ? 0 : (byte) parts[2].charAt(0);
            orderIds[i] = Long.parseLong(parts[3]);
            prices[i] = Long.parseLong(parts[4]);
            qtys[i] = Long.parseLong(parts[5]);
        }
        n = count;
    }

    private static List<Trade> loadExpectedTrades(List<String> lines) {
        if (lines == null) {
            throw new IllegalStateException("dataset missing trades.csv entry");
        }
        List<Trade> out = new ArrayList<>(Math.max(0, lines.size() - 1));
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            out.add(new Trade(
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2]),
                Side.valueOf(parts[3]),
                Long.parseLong(parts[4]),
                Long.parseLong(parts[5])));
        }
        return out;
    }

    /** Returns {@code key=value} pairs parsed from meta.txt lines; empty if absent. */
    private static Map<String, String> readMeta(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    private static PrecisionSpec precisionSpecFromMeta(Map<String, String> meta) {
        int priceScale = Integer.parseInt(meta.getOrDefault("priceScale", "0"));
        long priceTick = Long.parseLong(meta.getOrDefault("priceTick", "1"));
        int qtyScale = Integer.parseInt(meta.getOrDefault("qtyScale", "0"));
        long qtyStep = Long.parseLong(meta.getOrDefault("qtyStep", "1"));
        return PrecisionSpec.of(priceScale, priceTick, qtyScale, qtyStep);
    }

    /**
     * Read every entry of {@code /datasets/<name>.zip} into in-memory line lists.
     * The zip is small (≤ 10 MB compressed) and we hit each entry once per
     * trial setup, so eager full-read is simpler than streaming.
     */
    private static Map<String, List<String>> loadDatasetEntries(String datasetName) throws IOException {
        String resource = DATASET_RESOURCE_PREFIX + datasetName + ".zip";
        InputStream in = BookReplayBenchmark.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("dataset not found on classpath: " + resource
                + " (place a zip with events.csv / trades.csv / meta.txt under src/test/resources/datasets/)");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zin, StandardCharsets.UTF_8));
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                out.put(e.getName(), lines);
            }
        }
        return out;
    }

    // --- JMH entry point + per-run summary ----------------------------------

    public static void main(String[] args) throws Exception {
        Options opts = new OptionsBuilder()
            .parent(new CommandLineOptions(args))
            .include(BookReplayBenchmark.class.getSimpleName())
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
        String firstDataset = first.getParams().getParam("dataset");
        LocalDateTime now = LocalDateTime.now();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(now);

        Path outDir = Path.of("data", "jmh");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(stamp + "_" + firstDataset + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("# BookReplayBenchmark run\n");
        sb.append("timestamp=").append(now).append('\n');
        sb.append("dataset=").append(firstDataset).append('\n');

        Map<String, String> meta = readMetaForDataset(firstDataset);
        if (!meta.isEmpty()) {
            sb.append('\n').append("## dataset meta (from meta.txt)\n");
            meta.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        }

        sb.append('\n').append("## jmh config\n");
        sb.append("benchmark=").append(first.getParams().getBenchmark()).append('\n');
        sb.append("mode=").append(first.getParams().getMode()).append('\n');
        sb.append("warmupIterations=").append(first.getParams().getWarmup().getCount()).append('\n');
        sb.append("measurementIterations=")
            .append(first.getParams().getMeasurement().getCount()).append('\n');
        sb.append("forks=").append(first.getParams().getForks()).append('\n');
        sb.append("threads=").append(first.getParams().getThreads()).append('\n');

        sb.append('\n').append("## results (all timings in ns)\n");
        sb.append(String.format("%-18s %-22s %10s %16s %12s%n",
            "impl", "dataset", "events", "ns/replay", "ns/event"));
        for (RunResult r : results) {
            Result<?> primary = r.getPrimaryResult();
            String ds = r.getParams().getParam("dataset");
            long events = readOpsPerReplay(ds);
            double nsReplay = nanosPerReplay(primary.getScore(), primary.getScoreUnit());
            double nsEvent = events > 0 ? nsReplay / events : Double.NaN;
            sb.append(String.format("%-18s %-22s %10d %16.0f %12.3f%n",
                r.getParams().getParam("impl"),
                ds,
                events,
                nsReplay,
                nsEvent));
        }

        boolean hasSecondaryResults = results.stream()
            .anyMatch(r -> !r.getSecondaryResults().isEmpty());
        if (hasSecondaryResults) {
            sb.append('\n').append("## secondary results\n");
            sb.append(String.format("%-14s %-22s %-32s %14s %14s %s%n",
                "impl", "dataset", "metric", "score", "error", "units"));
            for (RunResult r : results) {
                String impl = r.getParams().getParam("impl");
                String ds = r.getParams().getParam("dataset");
                for (Result<?> secondary : r.getSecondaryResults().values()) {
                    sb.append(String.format("%-14s %-22s %-32s %14.3f %14.3f %s%n",
                        impl,
                        ds,
                        secondary.getLabel(),
                        secondary.getScore(),
                        Double.isNaN(secondary.getScoreError())
                            ? 0.0
                            : secondary.getScoreError(),
                        secondary.getScoreUnit()));
                }
            }
        }

        Files.writeString(outFile, sb.toString());
        System.out.println("[summary] wrote " + outFile.toAbsolutePath());
    }

    private static Map<String, String> readMetaForDataset(String datasetName) throws IOException {
        Map<String, List<String>> entries = loadDatasetEntries(datasetName);
        return readMeta(entries.getOrDefault("meta.txt", List.of()));
    }

    private static long readOpsPerReplay(String datasetName) throws IOException {
        Map<String, String> meta = readMetaForDataset(datasetName);
        String ops = meta.get("ops");
        return ops == null ? -1L : Long.parseLong(ops);
    }

    private static double nanosPerReplay(double score, String scoreUnit) {
        if (scoreUnit.startsWith("ns/")) {
            return score;
        }
        if (scoreUnit.startsWith("us/")) {
            return score * 1_000.0;
        }
        if (scoreUnit.startsWith("ms/")) {
            return score * 1_000_000.0;
        }
        if (scoreUnit.startsWith("s/")) {
            return score * 1_000_000_000.0;
        }
        return Double.NaN;
    }
}
