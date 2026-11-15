package games.cubi.utils.timings;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Must be called from a single consistent thread. That thread can be different to the threads the timers are being called on.
 */
public class MultithreadedProbe implements TimingProbe {
    private final CopyOnWriteArrayList<SingleThreadedTimer> singleTimers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SingleThreadedDoubleTimer> doubleTimers = new CopyOnWriteArrayList<>();

    private final IntArrayList values = new IntArrayList();

    @Override
    public void registerTimer(SingleThreadedTimer timer) {
        singleTimers.add(timer);
    }

    @Override
    public void registerTimer(SingleThreadedDoubleTimer timer) {
        doubleTimers.add(timer);
    }

    @Override
    // Best-effort probe, while a current timestamp can be misread as a duration if the array and position variables tear
    // that should be ignored due to being outside the quartile bands.
    public Quartiles probeAverage() {
        values.clear();
        singleTimers.forEach((timer) -> {
            for (int i = 0; i < timer.array.length ; i++) {
                if (i == timer.position) continue;
                values.add(timer.array[i]);
            }
        });

        doubleTimers.forEach((timer) -> {
            for (int i = 0; i < timer.arrayOne.length ; i++) {
                if (i != timer.positionOne) values.add(timer.arrayOne[i]);
                if (i != timer.positionTwo) values.add(timer.arrayTwo[i]);
            }
        });

        return TimingProbe.quartiles(values);
    }
}
