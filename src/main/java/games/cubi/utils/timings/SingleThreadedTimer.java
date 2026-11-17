package games.cubi.utils.timings;

public final class SingleThreadedTimer {
    int[] array;
    short position;
    int scratchOne;

    SingleThreadedTimer(short ringSize) {
        if (ringSize <= 0)
            throw new IllegalArgumentException("ringSize must be positive");

        array = new int[ringSize];
    }

    public void start() {
        scratchOne = (int) (System.nanoTime() >>> 4);
    }

    public void stop() {
        array[position] = (int) (System.nanoTime() >>> 4) - scratchOne;

        if (++position == array.length)
            position = 0;
    }

    long duration(int index) {
        return Integer.toUnsignedLong(array[index]) << 4;
    }

    public static SingleThreadedTimer with(TimingProbe probe, short ringSize) {
        SingleThreadedTimer timer = new SingleThreadedTimer(ringSize);
        probe.registerTimer(timer);
        return timer;
    }
}
