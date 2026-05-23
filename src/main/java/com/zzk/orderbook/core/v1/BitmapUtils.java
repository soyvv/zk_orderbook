package com.zzk.orderbook.core.v1;

import java.util.Arrays;

/**
 * Small allocation-free helpers for long-word bitmaps. Callers keep their
 * business logic inline while sharing the bit-scanning mechanics.
 */
final class BitmapUtils {

    static final int NOT_FOUND = -1;

    private BitmapUtils() {
    }

    static int nextSetBit(long[] words, int fromBit, int bitLimit) {
        if (fromBit >= bitLimit) {
            return NOT_FOUND;
        }
        int word = fromBit >>> 6;
        if (word >= words.length) {
            return NOT_FOUND;
        }

        long w = words[word] & (-1L << (fromBit & 63));
        while (true) {
            if (w != 0L) {
                int bit = (word << 6) + Long.numberOfTrailingZeros(w);
                return bit < bitLimit ? bit : NOT_FOUND;
            }

            word++;
            int nextBit = word << 6;
            if (nextBit >= bitLimit || word >= words.length) {
                return NOT_FOUND;
            }
            w = words[word];
        }
    }

    static boolean isEmpty(long[] words) {
        for (long word : words) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    static void clearAll(long[] words) {
        Arrays.fill(words, 0L);
    }
}
