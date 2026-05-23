package com.zzk.orderbook.perf;

import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Dataset I/O + parsing helpers used by {@link BookReplayBenchmark}. Keeps
 * the benchmark file focused on the JMH machinery and the hot replay loop.
 *
 * <p>Datasets are packaged as classpath resources at
 * {@code /datasets/<name>.zip}, each holding {@code events.csv},
 * {@code trades.csv}, and an optional {@code meta.txt}.
 */
final class DataSetUtils {

    private static final String DATASET_RESOURCE_PREFIX = "/datasets/";

    private DataSetUtils() {
    }

    /**
     * Parsed event stream in primitive form. Owns the per-column arrays the
     * benchmark's hot loop reads from. {@code n} is the count of valid
     * entries (each array is sized exactly to {@code n}).
     */
    record EventStream(
            byte[] ops,
            byte[] sides,
            long[] orderIds,
            long[] prices,
            long[] qtys,
            int n) {
    }

    // --- raw I/O ------------------------------------------------------------

    /**
     * Read every entry of {@code /datasets/<name>.zip} into in-memory line lists.
     * The zip is small (≤ 10 MB compressed) and we hit each entry once per
     * trial setup, so eager full-read is simpler than streaming.
     */
    static Map<String, List<String>> loadDatasetEntries(String datasetName) throws IOException {
        String resource = DATASET_RESOURCE_PREFIX + datasetName + ".zip";
        InputStream in = DataSetUtils.class.getResourceAsStream(resource);
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

    // --- per-entry parsing --------------------------------------------------

    /**
     * Parse {@code events.csv} into primitive parallel arrays.
     * Format: {@code seq,op,side,orderId,price,qty} (header on line 0).
     */
    static EventStream loadEvents(List<String> lines) {
        if (lines == null) {
            throw new IllegalStateException("dataset missing events.csv entry");
        }
        int count = lines.size() - 1;
        byte[] ops = new byte[count];
        byte[] sides = new byte[count];
        long[] orderIds = new long[count];
        long[] prices = new long[count];
        long[] qtys = new long[count];
        for (int i = 0; i < count; i++) {
            String[] parts = lines.get(i + 1).split(",", -1);
            ops[i] = (byte) parts[1].charAt(0);
            sides[i] = parts[2].isEmpty() ? 0 : (byte) parts[2].charAt(0);
            orderIds[i] = Long.parseLong(parts[3]);
            prices[i] = Long.parseLong(parts[4]);
            qtys[i] = Long.parseLong(parts[5]);
        }
        return new EventStream(ops, sides, orderIds, prices, qtys, count);
    }

    /**
     * Parse {@code trades.csv} into the expected trade sequence.
     * Format: {@code seq,makerOrderId,takerOrderId,takerSide,price,qty}.
     */
    static List<Trade> loadExpectedTrades(List<String> lines) {
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
    static Map<String, String> readMeta(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    static PrecisionSpec precisionSpecFromMeta(Map<String, String> meta) {
        int priceScale = Integer.parseInt(meta.getOrDefault("priceScale", "0"));
        long priceTick = Long.parseLong(meta.getOrDefault("priceTick", "1"));
        int qtyScale = Integer.parseInt(meta.getOrDefault("qtyScale", "0"));
        long qtyStep = Long.parseLong(meta.getOrDefault("qtyStep", "1"));
        return PrecisionSpec.of(priceScale, priceTick, qtyScale, qtyStep);
    }

    // --- convenience composites --------------------------------------------

    /** Load + parse meta.txt for a dataset in one call. */
    static Map<String, String> readMetaForDataset(String datasetName) throws IOException {
        return readMeta(loadDatasetEntries(datasetName).getOrDefault("meta.txt", List.of()));
    }

    /** Number of events the dataset advertises (from meta.txt's {@code ops} key), or {@code -1} if absent. */
    static long readOpsPerReplay(String datasetName) throws IOException {
        String ops = readMetaForDataset(datasetName).get("ops");
        return ops == null ? -1L : Long.parseLong(ops);
    }

    // --- validation ---------------------------------------------------------

    /**
     * Compare an actual trade stream against the recorded one element-wise.
     * Throws {@link IllegalStateException} with the offending index on first
     * mismatch — used by both the reference-replay validation and the
     * subject-replay validation in {@link BookReplayBenchmark#loadAndValidate}.
     */
    static void assertTradesEqual(List<Trade> expected, List<Trade> actual, String label) {
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

    // --- output-side formatting --------------------------------------------

    /**
     * Convert a JMH score in its native unit ({@code ns/op}, {@code us/op},
     * {@code ms/op}, {@code s/op}) into nanoseconds-per-replay. Returns
     * {@link Double#NaN} for unrecognized units.
     */
    static double nanosPerReplay(double score, String scoreUnit) {
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
