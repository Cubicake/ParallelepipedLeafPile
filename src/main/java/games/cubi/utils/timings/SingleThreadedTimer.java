package games.cubi.utils.timings;

public sealed class SingleThreadedTimer {
    int[] array;
    short position;

    SingleThreadedTimer(short ringSize) {
        if (ringSize <= 0)
            throw new IllegalArgumentException("ringSize must be positive");

        array = new int[ringSize];
    }

    private void start() {
        array[position] = (int) (System.nanoTime() >>> 4);
    }

    private void stop() {
        int start = array[position];
        array[position] = (int) (System.nanoTime() >>> 4) - start;

        if (++position == array.length)
            position = 0;
    }

    long duration(int index) {
        return Integer.toUnsignedLong(array[index]) << 4;
    }

    public static final class With extends SingleThreadedTimer {

        With(short ringSize) {
            super(ringSize);
        }

        public static SingleThreadedTimer.With probe(short ringSize, TimingProbe probe) {
            With timer = new With(ringSize);
            probe.registerTimer(timer);
            return timer;
        }

    }
}
