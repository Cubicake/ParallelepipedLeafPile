package games.cubi.utils.timings;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Timers have a resolution of 16ns or the JVM's timing resolution, whatever is greater.
 * Timers can time for a maximum of approximately one minute before overflowing.
 * <p>
 *     If a full loop of the buffers has not yet been completed, timings may report as zero
 * </p>
 */
public interface TimingProbe {
    void registerTimer(SingleThreadedTimer timer);
    void registerTimer(SingleThreadedDoubleTimer timer);

    DoubleQuartile probeAverage();

    /**
     * Values are in nanoseconds, however the resolution is 16 ns or greater
     */
    record Quartiles(long q1, long median, long q3) {
        private static final MathContext DISPLAY_PRECISION =
                new MathContext(5, RoundingMode.HALF_UP);

        private static String formatMillis(long nanos) {
            return BigDecimal.valueOf(nanos, 6)
                    .round(DISPLAY_PRECISION)
                    .stripTrailingZeros()
                    .toPlainString() + " ms";
        }

        @Override
        public @NotNull String toString() {
            return "q1: " + formatMillis(q1)
                    + " median: " + formatMillis(median)
                    + " q3: " + formatMillis(q3);
        }
    }
    record DoubleQuartile(Quartiles one, Quartiles two) {
        @Override
        public @NotNull String toString() {
            return "First timer quartiles: " + one.toString() + ", Second timer quartiles: " + two.toString();
        }
    }

    static Quartiles quartiles(IntArrayList values) {
        int n = values.size();
        if (n < 4) throw new IllegalArgumentException("Quartiles require at least 4 elements to be calculated");
        long[] sorted = new long[n];

        for (int i = 0; i < n; i++) {
            sorted[i] = Integer.toUnsignedLong(values.getInt(i));
        }

        Arrays.sort(sorted);

        int half = n / 2;
        return new Quartiles(
                median(sorted, 0, half),
                median(sorted, 0, n),
                median(sorted, n - half, n)
        );
    }

    // Median of the nonempty range [from, to).
    private static long median(long[] sorted, int from, int to) {
        int length = to - from;
        int mid = from + length / 2;

        if (length % 2 == 1) {
            return sorted[mid] << 4;
        }

        return (sorted[mid - 1] + sorted[mid]) << 3; // one less bit shift left means it has been halved compared to the << 4 equivalent, and since (x+y)/2 is the average of x and y this works
    }
}
