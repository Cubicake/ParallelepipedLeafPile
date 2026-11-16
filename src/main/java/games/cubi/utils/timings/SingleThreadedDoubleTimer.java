package games.cubi.utils.timings;

public final class SingleThreadedDoubleTimer {
    int[] arrayOne;
    int[] arrayTwo;
    short positionOne, positionTwo;
    int scratchOne, scratchTwo;

    SingleThreadedDoubleTimer(short ringSize) {
        if (ringSize <= 0)
            throw new IllegalArgumentException("ringSize must be positive");

        arrayOne = new int[ringSize];
        arrayTwo = new int[ringSize];
    }

    public void startOne() {
        scratchOne = (int) (System.nanoTime() >>> 4);
    }

    public void stopOne() {
        arrayOne[positionOne] = (int) (System.nanoTime() >>> 4) - scratchOne;

        if (++positionOne == arrayOne.length) positionOne = 0;
    }

    public void startTwo() {
        scratchTwo = (int) (System.nanoTime() >>> 4);
    }

    public void stopTwo() {
        arrayTwo[positionTwo] = (int) (System.nanoTime() >>> 4) - scratchTwo;

        if (++positionTwo == arrayTwo.length) positionTwo = 0;
    }

    public static SingleThreadedDoubleTimer with(TimingProbe probe, short ringSize) {
        SingleThreadedDoubleTimer timer = new SingleThreadedDoubleTimer(ringSize);
        probe.registerTimer(timer);
        return timer;
    }

}
