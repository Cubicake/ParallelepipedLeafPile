package games.cubi.utils.timings;

public class SingleThreadedDoubleTimer {
    int[] arrayOne;
    int[] arrayTwo;
    short positionOne, positionTwo;

    SingleThreadedDoubleTimer(short ringSize) {
        if (ringSize <= 0)
            throw new IllegalArgumentException("ringSize must be positive");

        arrayOne = new int[ringSize];
        arrayTwo = new int[ringSize];
    }

    private void startOne() {
        arrayOne[positionOne] = (int) (System.nanoTime() >>> 4);
    }

    private void stopOne() {
        int start = arrayOne[positionOne];
        arrayOne[positionOne] = (int) (System.nanoTime() >>> 4) - start;

        if (++positionOne == arrayOne.length) positionOne = 0;
    }

    private void startTwo() {
        arrayTwo[positionTwo] = (int) (System.nanoTime() >>> 4);
    }

    private void stopTwo() {
        int start = arrayTwo[positionTwo];
        arrayTwo[positionTwo] = (int) (System.nanoTime() >>> 4) - start;

        if (++positionTwo == arrayTwo.length) positionTwo = 0;
    }

}
