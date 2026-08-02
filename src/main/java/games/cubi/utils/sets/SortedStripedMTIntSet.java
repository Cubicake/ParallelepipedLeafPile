package games.cubi.utils.sets;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A thread-safe copy-on-write int set where values are partitioned into ascending stripes.
 * Designed for highly concurrent reads and rare writes.
 * <p></p>
 * Stripes are based on the low bits of the ints, and bits are not mixed before striping.
 * Therefore, this set works best when ints stored in it are evenly distributed.
 */
public class SortedStripedMTIntSet implements CopyOnWriteMTIntSet {
    private static final int TARGET_VALUES_PER_STRIPE = 50;

    private final Object writerLock = new Object();
    private volatile State state; private static final VarHandle STATE = ConcurrentUtil.getVarHandle(SortedStripedMTIntSet.class, "state", State.class);
    private int liveCount;

    public SortedStripedMTIntSet() {
        this(1);
    }

    public SortedStripedMTIntSet(int stripeCount) {
        if (stripeCount <= 0 || (stripeCount & (stripeCount - 1)) != 0) {
            throw new IllegalArgumentException("stripeCount must be a positive power of two: " + stripeCount);
        }

        int[][] stripes = new int[stripeCount][];
        Arrays.fill(stripes, new int[0]);
        STATE.setRelease(this, new State(stripes));
    }

    @Override
    public boolean contains(int value) {
        State stateSnapshot = (State) STATE.getAcquire(this);
        int[] stripe = getStripe(stateSnapshot, value);
        return Arrays.binarySearch(stripe, value) >= 0;
    }

    @Override
    public void add(int value) {
        synchronized (writerLock) {
            State oldState = (State) STATE.getAcquire(this);
            int[] oldStripe = getStripe(oldState, value);
            int searchResult = Arrays.binarySearch(oldStripe, value);
            if (searchResult >= 0) {
                return;
            }

            int insertionPoint = -searchResult - 1;
            int[] newStripe = insert(oldStripe, insertionPoint, value);
            int newLiveCount = liveCount + 1;
            State changedState = replaceStripe(oldState, value, newStripe);

            if ((long) newLiveCount > (long) oldState.stripes.length * TARGET_VALUES_PER_STRIPE) {
                STATE.setRelease(this, reStripe(changedState, newLiveCount));
            } else {
                STATE.setRelease(this, changedState);
            }
            liveCount = newLiveCount;
        }
    }

    @Override
    public boolean remove(int value) {
        synchronized (writerLock) {
            State oldState = (State) STATE.getAcquire(this);
            int[] oldStripe = getStripe(oldState, value);
            int searchResult = Arrays.binarySearch(oldStripe, value);
            if (searchResult < 0) {
                return false;
            }

            int[] newStripe = remove(oldStripe, searchResult);
            STATE.setRelease(this, replaceStripe(oldState, value, newStripe));
            liveCount--;
            return true;
        }
    }

    @Override
    public void forEach(IntConsumer consumer) {
        State stateSnapshot = (State) STATE.getAcquire(this);
        for (int[] stripe : stateSnapshot.stripes) {
            for (int value : stripe) {
                consumer.accept(value);
            }
        }
    }

    private static int[] insert(int[] values, int insertionPoint, int value) {
        int[] newValues = new int[values.length + 1];
        System.arraycopy(values, 0, newValues, 0, insertionPoint);
        newValues[insertionPoint] = value;
        System.arraycopy(values, insertionPoint, newValues, insertionPoint + 1, values.length - insertionPoint);
        return newValues;
    }

    private static int[] remove(int[] values, int removalPoint) {
        int[] newValues = new int[values.length - 1];
        System.arraycopy(values, 0, newValues, 0, removalPoint);
        System.arraycopy(values, removalPoint + 1, newValues, removalPoint, values.length - removalPoint - 1);
        return newValues;
    }

    private static int[] getStripe(State state, int value) {
        return state.stripes[stripeIndex(state, value)];
    }

    private static int stripeIndex(State state, int value) {
        return value & state.mask;
    }

    private static State replaceStripe(State oldState, int value, int[] newStripe) {
        int[][] newStripes = oldState.stripes.clone();
        newStripes[stripeIndex(oldState, value)] = newStripe;
        return new State(newStripes);
    }

    private static State reStripe(State oldState, int liveCount) {
        int stripeCount = requiredStripeCount(liveCount);
        int newMask = stripeCount - 1;
        int[] stripeSizes = new int[stripeCount];
        for (int[] stripe : oldState.stripes) {
            for (int value : stripe) {
                stripeSizes[value & newMask]++;
            }
        }

        int[][] newStripes = new int[stripeCount][];
        for (int index = 0; index < stripeCount; index++) {
            newStripes[index] = new int[stripeSizes[index]];
        }

        int[] stripePositions = new int[stripeCount];
        // Growing the power-of-two mask only splits each old sorted stripe into
        // filtered subsequences, so filling in source order preserves sorting.
        for (int[] stripe : oldState.stripes) {
            for (int value : stripe) {
                int stripeIndex = value & newMask;
                newStripes[stripeIndex][stripePositions[stripeIndex]++] = value;
            }
        }
        return new State(newStripes);
    }

    private static int requiredStripeCount(int liveCount) {
        long valuesPerStripe = (liveCount + (long) TARGET_VALUES_PER_STRIPE - 1) / TARGET_VALUES_PER_STRIPE;
        int stripeCount = 1;
        while (stripeCount < valuesPerStripe) {
            stripeCount <<= 1;
        }
        return stripeCount;
    }

    private static final class State {
        private final int[][] stripes;
        private final int mask;

        private State(int[][] stripes) {
            this.stripes = stripes;
            this.mask = stripes.length - 1;
        }
    }
}
