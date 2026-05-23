package com.zzk.orderbook.perf;

import com.zzk.orderbook.core.L3OrderBook;
import com.zzk.orderbook.core.ref.TreeMapOrderBook;
import com.zzk.orderbook.model.Order;
import com.zzk.orderbook.model.PrecisionSpec;
import com.zzk.orderbook.model.PriceLevel;
import com.zzk.orderbook.model.Side;
import com.zzk.orderbook.model.Trade;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates valid CSV event/trade streams by applying candidate operations to
 * {@link TreeMapOrderBook}. The reference book is the source of truth for
 * matching, residual resting orders, and maker removals.
 */
public final class CsvDatasetGenerator {

    private static final Map<String, Integer> PROFILE_SIZES = Map.of(
        "small", 10_000,
        "medium", 1_000_000,
        "large", 10_000_000
    );

    private final Config config;
    private final Random random;
    private final L3OrderBook book;
    private final ArrayList<Long> activeOrderIds = new ArrayList<>();
    private final HashMap<Long, Integer> activePositions = new HashMap<>();
    private long nextOrderId = 1;
    private long burstCenter;
    private int burstOpsRemaining;
    private long generatedTradeCount;
    private int peakActiveOrderCount;

    private CsvDatasetGenerator(Config config) {
        this.config = config;
        this.random = new Random(config.seed);
        this.book = new TreeMapOrderBook(PrecisionSpec.of(
            config.priceScale, config.priceTick, config.qtyScale, config.qtyStep));
        this.burstCenter = config.midPrice;
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.parse(args);
        new CsvDatasetGenerator(config).generate();
    }

    private void generate() throws IOException {
        createParent(config.eventsOut);
        createParent(config.tradesOut);
        if (config.metaOut != null) {
            createParent(config.metaOut);
        }

        try (BufferedWriter events = Files.newBufferedWriter(config.eventsOut);
            BufferedWriter trades = Files.newBufferedWriter(config.tradesOut)) {
            events.write("seq,op,side,order_id,price,qty\n");
            trades.write("seq,maker_order_id,taker_order_id,taker_side,price,qty\n");

            for (long seq = 1; seq <= config.ops; seq++) {
                Op op = chooseOp();
                switch (op) {
                    case ADD -> writeAdd(seq, events, trades);
                    case UPDATE -> writeUpdate(seq, events);
                    case REMOVE -> writeRemove(seq, events);
                }
                peakActiveOrderCount = Math.max(peakActiveOrderCount, activeOrderIds.size());
            }
        }

        if (config.metaOut != null) {
            writeMeta();
        }
    }

    private void writeMeta() throws IOException {
        try (BufferedWriter meta = Files.newBufferedWriter(config.metaOut)) {
            meta.write("generator=CsvDatasetGenerator\n");
            meta.write("profile=" + config.profile + "\n");
            meta.write("distribution=" + config.distribution + "\n");
            meta.write("seed=" + config.seed + "\n");
            meta.write("eventsOut=" + config.eventsOut + "\n");
            meta.write("tradesOut=" + config.tradesOut + "\n");
            meta.write("addRateNormalized=" + config.addRate + "\n");
            meta.write("updateRateNormalized=" + config.updateRate + "\n");
            meta.write("removeRateNormalized=" + config.removeRate + "\n");
            meta.write("ops=" + config.ops + "\n");
            meta.write("trades=" + generatedTradeCount + "\n");
            meta.write("peakActiveOrders=" + peakActiveOrderCount + "\n");
            meta.write("midPrice=" + config.midPrice + "\n");
            meta.write("priceScale=" + config.priceScale + "\n");
            meta.write("priceTick=" + config.priceTick + "\n");
            meta.write("qtyScale=" + config.qtyScale + "\n");
            meta.write("qtyStep=" + config.qtyStep + "\n");
            meta.write("minQtySteps=" + config.minQtySteps + "\n");
            meta.write("maxQtySteps=" + config.maxQtySteps + "\n");
            meta.write("clusteredWidthTicks=" + config.clusteredWidthTicks + "\n");
            meta.write("wideWidthTicks=" + config.wideWidthTicks + "\n");
            meta.write("wideWidthPct=" + config.wideWidthPct + "\n");
            meta.write("burstWidthTicks=" + config.burstWidthTicks + "\n");
            meta.write("burstLength=" + config.burstLength + "\n");
            meta.write("priceAnchor=" + config.priceAnchor + "\n");
            meta.write("bboWidthTicks=" + config.bboWidthTicks + "\n");
            meta.write("bboWidthPct=" + config.bboWidthPct + "\n");
            meta.write("crossRate=" + config.crossRate + "\n");
            meta.write("improveQuoteRate=" + config.improveQuoteRate + "\n");
            meta.write("activeSelection=" + config.activeSelection + "\n");
            meta.write("bboSelectWidthTicks=" + config.bboSelectWidthTicks + "\n");
            meta.write("bboSelectionSamples=" + config.bboSelectionSamples + "\n");
            meta.write("allowCrossing=" + config.allowCrossing + "\n");
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Op chooseOp() {
        if (activeOrderIds.isEmpty()) {
            return Op.ADD;
        }

        double roll = random.nextDouble();
        if (roll < config.addRate) {
            return Op.ADD;
        }
        if (roll < config.addRate + config.updateRate) {
            return Op.UPDATE;
        }
        return Op.REMOVE;
    }

    private void writeAdd(long seq, BufferedWriter events, BufferedWriter trades) throws IOException {
        long orderId = nextOrderId++;
        Side side = random.nextBoolean() ? Side.BID : Side.ASK;
        if (!config.allowCrossing && side == Side.BID) {
            PriceLevel bestAsk = book.getByLevel(Side.ASK, 0);
            if (bestAsk != null && bestAsk.price() <= config.priceTick) {
                side = Side.ASK;
            }
        }
        long price = price(side);
        long quantity = quantity();

        List<Trade> fills = book.add(orderId, side, price, quantity);
        events.write(seq + ",A," + csvSide(side) + "," + orderId + "," + price + "," + quantity + "\n");

        for (Trade fill : fills) {
            trades.write(seq + "," + fill.makerOrderId() + "," + fill.takerOrderId()
                + "," + fill.takerSide() + "," + fill.price() + "," + fill.quantity() + "\n");
            generatedTradeCount++;
            if (book.getOrder(fill.makerOrderId()) == null) {
                removeActive(fill.makerOrderId());
            }
        }

        if (book.getOrder(orderId) != null) {
            addActive(orderId);
        }
    }

    private void writeUpdate(long seq, BufferedWriter events) throws IOException {
        long orderId = selectActiveOrderId();
        long quantity = quantity();

        book.update(orderId, quantity);
        events.write(seq + ",U,," + orderId + ",0," + quantity + "\n");
    }

    private void writeRemove(long seq, BufferedWriter events) throws IOException {
        long orderId = selectActiveOrderId();

        book.remove(orderId);
        removeActive(orderId);
        events.write(seq + ",R,," + orderId + ",0,0\n");
    }

    private long selectActiveOrderId() {
        if ("bbo".equals(config.activeSelection)) {
            long orderId = selectBboActiveOrderId();
            if (orderId != 0) {
                return orderId;
            }
        }
        return randomActiveOrderId();
    }

    private long randomActiveOrderId() {
        return activeOrderIds.get(random.nextInt(activeOrderIds.size()));
    }

    private long selectBboActiveOrderId() {
        long maxDistance = config.bboSelectWidthTicks * config.priceTick;
        int samples = Math.min(config.bboSelectionSamples, activeOrderIds.size());

        for (int i = 0; i < samples; i++) {
            long orderId = randomActiveOrderId();
            Order order = book.getOrder(orderId);
            if (order == null) {
                continue;
            }

            PriceLevel best = book.getByLevel(order.side(), 0);
            if (best == null) {
                continue;
            }

            long distance = order.side() == Side.BID
                ? best.price() - order.price()
                : order.price() - best.price();
            if (distance >= 0 && distance <= maxDistance) {
                return orderId;
            }
        }

        return 0;
    }

    private void addActive(long orderId) {
        activePositions.put(orderId, activeOrderIds.size());
        activeOrderIds.add(orderId);
    }

    private void removeActive(long orderId) {
        Integer pos = activePositions.remove(orderId);
        if (pos == null) {
            return;
        }

        int lastPos = activeOrderIds.size() - 1;
        long lastOrderId = activeOrderIds.remove(lastPos);
        if (pos < lastPos) {
            activeOrderIds.set(pos, lastOrderId);
            activePositions.put(lastOrderId, pos);
        }
    }

    private long price(Side side) {
        if ("bbo".equals(config.priceAnchor)) {
            long price = bboAnchoredPrice(side);
            return config.allowCrossing ? price : passivePrice(side, price);
        }

        long price = switch (config.distribution) {
            case "clustered" -> config.midPrice
                + Math.round(random.nextGaussian() * config.clusteredWidthTicks) * config.priceTick;
            case "wide" -> config.midPrice
                + randomOffset(wideWidthTicks()) * config.priceTick;
            case "bursty" -> burstPrice();
            default -> throw new IllegalStateException("unsupported distribution: " + config.distribution);
        };

        price = Math.max(config.priceTick, price);
        return config.allowCrossing ? price : passivePrice(side, price);
    }

    private long bboAnchoredPrice(Side side) {
        PriceLevel sameBest = book.getByLevel(side, 0);
        PriceLevel oppositeBest = book.getByLevel(opposite(side), 0);

        if (config.allowCrossing && oppositeBest != null && random.nextDouble() < config.crossRate) {
            return aggressivePrice(side, oppositeBest.price());
        }

        if (side == Side.BID) {
            return passiveBidNearBbo(sameBest, oppositeBest);
        }
        return passiveAskNearBbo(sameBest, oppositeBest);
    }

    private long aggressivePrice(Side side, long oppositeBestPrice) {
        long offset = bboOffset();
        if (side == Side.BID) {
            return oppositeBestPrice + offset;
        }
        return Math.max(config.priceTick, oppositeBestPrice - offset);
    }

    private long passiveBidNearBbo(PriceLevel bestBid, PriceLevel bestAsk) {
        if (bestBid != null) {
            if (bestAsk != null && canImproveInsideSpread(bestBid.price(), bestAsk.price())) {
                return randomAlignedBetween(bestBid.price() + config.priceTick, bestAsk.price() - config.priceTick);
            }
            return Math.max(config.priceTick, bestBid.price() - bboOffset());
        }
        if (bestAsk != null) {
            return Math.max(config.priceTick, bestAsk.price() - config.priceTick - bboOffset());
        }
        return fallbackMidPrice(Side.BID);
    }

    private long passiveAskNearBbo(PriceLevel bestAsk, PriceLevel bestBid) {
        if (bestAsk != null) {
            if (bestBid != null && canImproveInsideSpread(bestBid.price(), bestAsk.price())) {
                return randomAlignedBetween(bestBid.price() + config.priceTick, bestAsk.price() - config.priceTick);
            }
            return bestAsk.price() + bboOffset();
        }
        if (bestBid != null) {
            return bestBid.price() + config.priceTick + bboOffset();
        }
        return fallbackMidPrice(Side.ASK);
    }

    private boolean canImproveInsideSpread(long bestBid, long bestAsk) {
        return bestAsk - bestBid > config.priceTick && random.nextDouble() < config.improveQuoteRate;
    }

    private long randomAlignedBetween(long minPrice, long maxPrice) {
        if (maxPrice <= minPrice) {
            return minPrice;
        }
        long ticks = (maxPrice - minPrice) / config.priceTick;
        return minPrice + random.nextLong(ticks + 1) * config.priceTick;
    }

    private long fallbackMidPrice(Side side) {
        long price = switch (config.distribution) {
            case "clustered" -> config.midPrice
                + Math.round(random.nextGaussian() * config.clusteredWidthTicks) * config.priceTick;
            case "wide" -> config.midPrice
                + randomOffset(wideWidthTicks()) * config.priceTick;
            case "bursty" -> burstPrice();
            default -> throw new IllegalStateException("unsupported distribution: " + config.distribution);
        };
        return passivePrice(side, Math.max(config.priceTick, price));
    }

    private long bboOffset() {
        return random.nextLong(bboWidthTicks() + 1) * config.priceTick;
    }

    private long bboWidthTicks() {
        if (config.bboWidthPct > 0.0) {
            return Math.max(1L, Math.round(referenceBboPrice() * config.bboWidthPct / config.priceTick));
        }
        return config.bboWidthTicks;
    }

    private long referenceBboPrice() {
        PriceLevel bestBid = book.getByLevel(Side.BID, 0);
        PriceLevel bestAsk = book.getByLevel(Side.ASK, 0);
        if (bestBid != null && bestAsk != null) {
            return Math.max(config.priceTick, (bestBid.price() + bestAsk.price()) / 2L);
        }
        if (bestBid != null) {
            return bestBid.price();
        }
        if (bestAsk != null) {
            return bestAsk.price();
        }
        return config.midPrice;
    }

    private static Side opposite(Side side) {
        return side == Side.BID ? Side.ASK : Side.BID;
    }

    private long burstPrice() {
        if (burstOpsRemaining <= 0) {
            burstCenter = Math.max(config.priceTick,
                config.midPrice + randomOffset(wideWidthTicks()) * config.priceTick);
            burstOpsRemaining = config.burstLength;
        }

        burstOpsRemaining--;
        return Math.max(config.priceTick, burstCenter + randomOffset(config.burstWidthTicks) * config.priceTick);
    }

    private long randomOffset(int widthTicks) {
        return random.nextInt(widthTicks * 2 + 1) - (long) widthTicks;
    }

    private long randomOffset(long widthTicks) {
        return random.nextLong(widthTicks * 2L + 1L) - widthTicks;
    }

    private long wideWidthTicks() {
        if (config.wideWidthPct > 0.0) {
            return Math.max(1L, Math.round(config.midPrice * config.wideWidthPct / config.priceTick));
        }
        return config.wideWidthTicks;
    }

    private long passivePrice(Side side, long price) {
        if (side == Side.BID) {
            PriceLevel bestAsk = book.getByLevel(Side.ASK, 0);
            if (bestAsk != null) {
                return Math.max(config.priceTick, Math.min(price, bestAsk.price() - config.priceTick));
            }
        } else {
            PriceLevel bestBid = book.getByLevel(Side.BID, 0);
            if (bestBid != null) {
                return Math.max(config.priceTick, Math.max(price, bestBid.price() + config.priceTick));
            }
        }
        return price;
    }

    private long quantity() {
        int steps = config.minQtySteps + random.nextInt(config.maxQtySteps - config.minQtySteps + 1);
        return steps * config.qtyStep;
    }

    private static String csvSide(Side side) {
        return side == Side.BID ? "B" : "A";
    }

    private enum Op {
        ADD,
        UPDATE,
        REMOVE
    }

    private record Config(
        String profile,
        String distribution,
        long seed,
        Path eventsOut,
        Path tradesOut,
        Path metaOut,
        double addRate,
        double updateRate,
        double removeRate,
        int ops,
        long midPrice,
        int priceScale,
        long priceTick,
        int qtyScale,
        long qtyStep,
        int minQtySteps,
        int maxQtySteps,
        int clusteredWidthTicks,
        int wideWidthTicks,
        double wideWidthPct,
        int burstWidthTicks,
        int burstLength,
        String priceAnchor,
        int bboWidthTicks,
        double bboWidthPct,
        double crossRate,
        double improveQuoteRate,
        String activeSelection,
        int bboSelectWidthTicks,
        int bboSelectionSamples,
        boolean allowCrossing
    ) {

        static Config parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            boolean allowCrossing = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit(0);
                }
                if ("--allow-crossing".equals(arg)) {
                    allowCrossing = true;
                    continue;
                }
                if (!arg.startsWith("--")) {
                    die("unexpected argument: " + arg);
                }
                if (i + 1 >= args.length) {
                    die("missing value for " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }

            String profile = stringValue(values, "profile", "small");
            if (!PROFILE_SIZES.containsKey(profile)) {
                die("--profile must be one of small, medium, large");
            }

            String distribution = stringValue(values, "dist", "clustered");
            if (!List.of("clustered", "wide", "bursty").contains(distribution)) {
                die("--dist must be one of clustered, wide, bursty");
            }

            String priceAnchor = stringValue(values, "price-anchor", "mid");
            if (!List.of("mid", "bbo").contains(priceAnchor)) {
                die("--price-anchor must be one of mid, bbo");
            }

            String activeSelection = stringValue(values, "active-selection", "random");
            if (!List.of("random", "bbo").contains(activeSelection)) {
                die("--active-selection must be one of random, bbo");
            }

            Path eventsOut = Path.of(required(values, "events-out"));
            Path tradesOut = Path.of(required(values, "trades-out"));
            Path metaOut = optionalPath(values, "meta-out");
            int ops = intValue(values, "ops", PROFILE_SIZES.get(profile));

            double addRate = doubleValue(values, "add-rate", 0.50);
            double updateRate = doubleValue(values, "update-rate", 0.30);
            double removeRate = doubleValue(values, "remove-rate", 0.20);
            if (addRate < 0 || updateRate < 0 || removeRate < 0) {
                die("operation rates must be >= 0");
            }
            double totalRate = addRate + updateRate + removeRate;
            if (totalRate <= 0) {
                die("at least one operation rate must be > 0");
            }

            double crossRate = doubleValue(values, "cross-rate", 0.10);
            double improveQuoteRate = doubleValue(values, "improve-quote-rate", 0.20);
            double wideWidthPct = doubleValue(values, "wide-width-pct", 0.0);
            double bboWidthPct = doubleValue(values, "bbo-width-pct", 0.0);
            if (crossRate < 0 || crossRate > 1) {
                die("--cross-rate must be between 0 and 1");
            }
            if (improveQuoteRate < 0 || improveQuoteRate > 1) {
                die("--improve-quote-rate must be between 0 and 1");
            }
            if (wideWidthPct < 0 || wideWidthPct > 1) {
                die("--wide-width-pct must be between 0 and 1");
            }
            if (bboWidthPct < 0 || bboWidthPct > 1) {
                die("--bbo-width-pct must be between 0 and 1");
            }

            int minQtySteps = intValue(values, "min-qty-steps", 1);
            int maxQtySteps = intValue(values, "max-qty-steps", 100);
            if (maxQtySteps < minQtySteps) {
                die("--max-qty-steps must be >= --min-qty-steps");
            }

            String priceTickInput = stringValue(values, "price-tick", "1");
            int priceScale = decimalScale(priceTickInput);
            long priceTick = positive("price-tick", decimalToScaledLong(
                priceTickInput, priceScale, "price-tick"));

            String qtyStepInput = stringValue(values, "qty-step", "1");
            int qtyScale = decimalScale(qtyStepInput);
            long qtyStep = positive("qty-step", decimalToScaledLong(
                qtyStepInput, qtyScale, "qty-step"));

            return new Config(
                profile,
                distribution,
                longValue(values, "seed", 42L),
                eventsOut,
                tradesOut,
                metaOut,
                addRate / totalRate,
                updateRate / totalRate,
                removeRate / totalRate,
                positive("ops", ops),
                positive("mid-price", decimalToScaledLong(
                    stringValue(values, "mid-price", "1000000"), priceScale, "mid-price")),
                priceScale,
                priceTick,
                qtyScale,
                qtyStep,
                positive("min-qty-steps", minQtySteps),
                positive("max-qty-steps", maxQtySteps),
                positive("clustered-width-ticks", intValue(values, "clustered-width-ticks", 20)),
                positive("wide-width-ticks", intValue(values, "wide-width-ticks", 1_000)),
                wideWidthPct,
                positive("burst-width-ticks", intValue(values, "burst-width-ticks", 5)),
                positive("burst-length", intValue(values, "burst-length", 200)),
                priceAnchor,
                positive("bbo-width-ticks", intValue(values, "bbo-width-ticks", 20)),
                bboWidthPct,
                crossRate,
                improveQuoteRate,
                activeSelection,
                positive("bbo-select-width-ticks", intValue(values, "bbo-select-width-ticks", 100)),
                positive("bbo-selection-samples", intValue(values, "bbo-selection-samples", 64)),
                allowCrossing
            );
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                die("missing required --" + key);
            }
            return value;
        }

        private static String stringValue(Map<String, String> values, String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        private static Path optionalPath(Map<String, String> values, String key) {
            String value = values.get(key);
            return value == null || value.isBlank() ? null : Path.of(value);
        }

        private static int intValue(Map<String, String> values, String key, int defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Integer.parseInt(value);
        }

        private static long longValue(Map<String, String> values, String key, long defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Long.parseLong(value);
        }

        private static int decimalScale(String value) {
            BigDecimal decimal = new BigDecimal(value);
            return Math.max(0, decimal.stripTrailingZeros().scale());
        }

        private static long decimalToScaledLong(String value, int scale, String name) {
            BigDecimal scaled = new BigDecimal(value).movePointRight(scale);
            try {
                return scaled.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            } catch (ArithmeticException e) {
                die("--" + name + " must align to scale " + scale + ": " + value);
                return 0L;
            }
        }

        private static double doubleValue(Map<String, String> values, String key, double defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Double.parseDouble(value);
        }

        private static int positive(String name, int value) {
            if (value <= 0) {
                die("--" + name + " must be > 0");
            }
            return value;
        }

        private static long positive(String name, long value) {
            if (value <= 0) {
                die("--" + name + " must be > 0");
            }
            return value;
        }

        private static void die(String message) {
            System.err.println(message);
            printUsageAndExit(2);
        }

        private static void printUsageAndExit(int code) {
            System.out.println("""
                    Usage:
                      java -cp target/classes com.zzk.orderbook.perf.CsvDatasetGenerator \\
                        --events-out data/generated/events.csv \\
                        --trades-out data/generated/trades.csv \\
                        [--meta-out data/generated/meta.txt] \\
                        [--profile small|medium|large] [--ops N] [--dist clustered|wide|bursty] \\
                        [--seed N] [--add-rate R] [--update-rate R] [--remove-rate R] \\
                        [--price-anchor mid|bbo] [--active-selection random|bbo] \\
                        [--wide-width-pct R] [--bbo-width-pct R] \\
                        [--bbo-width-ticks N] [--bbo-select-width-ticks N] \\
                        [--bbo-selection-samples N] \\
                        [--cross-rate R] [--improve-quote-rate R] \\
                        [--mid-price N] [--price-tick N] [--qty-step N] \\
                        [--min-qty-steps N] [--max-qty-steps N] \\
                        [--clustered-width-ticks N] [--wide-width-ticks N] \\
                        [--burst-width-ticks N] [--burst-length N] [--allow-crossing]

                    Outputs:
                      events: seq,op,side,order_id,price,qty
                      trades: seq,maker_order_id,taker_order_id,taker_side,price,qty

                    By default prices are anchored around --mid-price. Use
                    --price-anchor bbo to place adds around the current best
                    bid/offer. Use --active-selection bbo to bias updates and
                    cancels toward orders near top of book. In BBO mode,
                    --cross-rate controls aggressive adds when --allow-crossing
                    is present. Percentage width options are fractions, so 0.10
                    means 10% of the reference mid/BBO price.
                    """);
            System.exit(code);
        }
    }
}
