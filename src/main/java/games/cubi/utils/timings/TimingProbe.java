package games.cubi.utils.timings;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;

public interface TimingProbe {
    void registerTimer(SingleThreadedTimer timer);
    void registerTimer(SingleThreadedDoubleTimer timer);

    Quartiles probeAverage();

    record Quartiles(double q1, double median, double q3) {}

    static Quartiles quartiles(IntArrayList values) {
        int[] sorted = values.toIntArray();
        int n = sorted.length;
        if (n == 0) {
            throw new IllegalArgumentException("Quartiles require at least one value");
        }
        if (n == 1) {
            return new Quartiles(sorted[0], sorted[0], sorted[0]);
        }

        Arrays.sort(sorted);
        int half = n / 2;
        return new Quartiles(
                median(sorted, 0, half),
                median(sorted, 0, n),
                median(sorted, n - half, n));
    }

    // Median of the nonempty range [from, to).
    private static double median(int[] sorted, int from, int to) {
        int length = to - from;
        int mid = from + length / 2;
        if (length % 2 == 1) {
            return sorted[mid];
        }
        return ((long) sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
