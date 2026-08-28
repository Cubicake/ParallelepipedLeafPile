/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box contract shared by both cancellable event registry implementations.
 */
abstract class AbstractCancellableEventRegistryContractTest {

    protected abstract CancellableEventRegistry<TestEvent> createRegistry();

    protected abstract Class<? extends RuntimeException> duplicateNormalKeyException();

    @Test
    void emptyRegistryAvoidsLazyEventCreationAndHonoursPreCancellation() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicBoolean eventCreated = new AtomicBoolean();

        assertTrue(registry.isEmpty());
        assertTrue(registry.dispatch(() -> {
            eventCreated.set(true);
            return new TestEvent();
        }));
        assertFalse(eventCreated.get());

        TestEvent preCancelled = new TestEvent();
        preCancelled.cancel();
        assertFalse(registry.dispatch(preCancelled));
        assertTrue(preCancelled.isCancelled());
    }

    @Test
    void populatedRegistryCreatesEachLazyEventExactlyOnce() {
        CancellableEventRegistry<TestEvent> handlers = createRegistry();
        CancellableEventRegistry<TestEvent> monitors = createRegistry();
        AtomicInteger handlerEvents = new AtomicInteger();
        AtomicInteger monitorEvents = new AtomicInteger();

        handlers.registerUnconditional(key("handler"), event -> event.value++);
        monitors.registerUnconditionalMonitor(key("monitor"), event -> event.value++);

        assertTrue(handlers.dispatch(() -> {
            handlerEvents.incrementAndGet();
            return new TestEvent();
        }));
        assertTrue(monitors.dispatch(() -> {
            monitorEvents.incrementAndGet();
            return new TestEvent();
        }));
        assertEquals(1, handlerEvents.get());
        assertEquals(1, monitorEvents.get());
    }

    @Test
    void normalHandlersRunFifoBeforeMonitors() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditional(key("normal-1"), event -> trace.append("normal-1,"));
        registry.registerConditional(key("normal-2"), event -> trace.append("normal-2,"));
        registry.registerUnconditionalMonitor(key("monitor-1"), event -> trace.append("monitor-1,"));
        registry.registerConditionalMonitor(key("monitor-2"), event -> trace.append("monitor-2,"));

        assertTrue(registry.dispatch(new TestEvent()));
        assertEquals("normal-1,normal-2,monitor-1,monitor-2,", trace.toString());
    }

    @Test
    void cancellationControlsConditionalHandlersAtThePointTheyAreReached() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        StringBuilder trace = new StringBuilder();

        registry.registerConditional(key("initial"), event -> trace.append("initial,"));
        registry.registerUnconditional(key("cancel"), event -> {
            trace.append("cancel,");
            event.cancel();
        });
        registry.registerConditional(key("skipped"), event -> trace.append("skipped,"));
        registry.registerUnconditional(key("uncancel"), event -> {
            trace.append("uncancel,");
            event.setCancelled(false);
        });
        registry.registerConditional(key("resumed"), event -> trace.append("resumed,"));

        TestEvent event = new TestEvent();
        assertTrue(registry.dispatch(event));
        assertFalse(event.isCancelled());
        assertEquals("initial,cancel,uncancel,resumed,", trace.toString());
    }

    @Test
    void preCancelledEventSkipsConditionalHandlersButStillRunsUnconditionalHandlers() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicBoolean conditionalRan = new AtomicBoolean();
        AtomicBoolean unconditionalRan = new AtomicBoolean();

        registry.registerConditional(key("conditional"), event -> conditionalRan.set(true));
        registry.registerUnconditional(key("unconditional"), event -> unconditionalRan.set(true));

        TestEvent event = new TestEvent();
        event.cancel();
        assertFalse(registry.dispatch(event));
        assertFalse(conditionalRan.get());
        assertTrue(unconditionalRan.get());
        assertTrue(event.isCancelled());
    }

    @Test
    void monitorsObserveAndCannotChangeNormalHandlerCancellation() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicBoolean firstMonitorSawCancellation = new AtomicBoolean();
        AtomicBoolean secondMonitorSawCancellation = new AtomicBoolean();

        registry.registerUnconditional(key("cancel"), CancellableEvent::cancel);
        registry.registerUnconditionalMonitor(key("uncancelling-monitor"), event -> {
            firstMonitorSawCancellation.set(event.isCancelled());
            event.setCancelled(false);
            event.value++;
        });
        registry.registerUnconditionalMonitor(key("observing-monitor"), event ->
                secondMonitorSawCancellation.set(event.isCancelled()));

        TestEvent event = new TestEvent();
        assertFalse(registry.dispatch(event));
        assertTrue(firstMonitorSawCancellation.get());
        assertTrue(secondMonitorSawCancellation.get());
        assertTrue(event.isCancelled());
        assertEquals(1, event.value, "non-cancellation mutations made by monitors should remain visible");
    }

    @Test
    void monitorCancellationIsResetBeforeConditionalMonitors() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicBoolean conditionalMonitorRan = new AtomicBoolean();

        registry.registerUnconditionalMonitor(key("cancelling-monitor"), CancellableEvent::cancel);
        registry.registerConditionalMonitor(key("conditional-monitor"), event -> conditionalMonitorRan.set(true));

        TestEvent event = new TestEvent();
        assertTrue(registry.dispatch(event));
        assertTrue(conditionalMonitorRan.get());
        assertFalse(event.isCancelled());
    }

    @Test
    void conditionalMonitorsSkipEventsCancelledByNormalHandlers() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicBoolean conditionalMonitorRan = new AtomicBoolean();
        AtomicBoolean unconditionalMonitorRan = new AtomicBoolean();

        registry.registerUnconditional(key("cancel"), CancellableEvent::cancel);
        registry.registerConditionalMonitor(key("conditional-monitor"), event -> conditionalMonitorRan.set(true));
        registry.registerUnconditionalMonitor(key("unconditional-monitor"), event -> unconditionalMonitorRan.set(true));

        assertFalse(registry.dispatch(new TestEvent()));
        assertFalse(conditionalMonitorRan.get());
        assertTrue(unconditionalMonitorRan.get());
    }

    @Test
    void normalHandlerFailurePropagatesAndStopsDispatch() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        ExpectedException expected = new ExpectedException();
        AtomicBoolean laterNormalRan = new AtomicBoolean();
        AtomicBoolean monitorRan = new AtomicBoolean();

        registry.registerUnconditional(key("throwing-normal"), event -> {
            throw expected;
        });
        registry.registerUnconditional(key("later-normal"), event -> laterNormalRan.set(true));
        registry.registerUnconditionalMonitor(key("monitor"), event -> monitorRan.set(true));

        assertSame(expected, assertThrows(ExpectedException.class, () -> registry.dispatch(new TestEvent())));
        assertFalse(laterNormalRan.get());
        assertFalse(monitorRan.get());
    }

    @Test
    void monitorFailurePropagatesStopsLaterMonitorsAndRestoresCancellation() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        ExpectedException expected = new ExpectedException();
        AtomicBoolean laterMonitorRan = new AtomicBoolean();

        registry.registerUnconditional(key("cancel"), CancellableEvent::cancel);
        registry.registerUnconditionalMonitor(key("throwing-monitor"), event -> {
            event.setCancelled(false);
            throw expected;
        });
        registry.registerUnconditionalMonitor(key("later-monitor"), event -> laterMonitorRan.set(true));

        TestEvent event = new TestEvent();
        assertSame(expected, assertThrows(ExpectedException.class, () -> registry.dispatch(event)));
        assertFalse(laterMonitorRan.get());
        assertTrue(event.isCancelled());
    }

    @Test
    void registrationAndRemovalRejectNullArguments() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey key = key("valid");

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditional(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditional(key, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerConditional(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerConditional(key, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalMonitor(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalMonitor(key, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerConditionalMonitor(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerConditionalMonitor(key, null)),
                () -> assertThrows(NullPointerException.class, () -> registry.unregister(null)),
                () -> assertThrows(NullPointerException.class, () -> registry.unregisterMonitor(null))
        );
        assertTrue(registry.isEmpty());
    }

    @Test
    void duplicateNormalKeyIsRejectedWithoutReplacingTheOriginalHandler() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey duplicate = key("duplicate-normal");
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditional(duplicate, event -> trace.append("original,"));
        assertThrows(duplicateNormalKeyException(),
                () -> registry.registerUnconditional(duplicate, event -> trace.append("replacement,")));

        registry.dispatch(new TestEvent());
        assertEquals("original,", trace.toString());
    }

    @Test
    void duplicateMonitorKeyIsRejectedWithoutReplacingTheOriginalMonitor() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey duplicate = key("duplicate-monitor");
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditionalMonitor(duplicate, event -> trace.append("original,"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerUnconditionalMonitor(duplicate, event -> trace.append("replacement,")));

        registry.dispatch(new TestEvent());
        assertEquals("original,", trace.toString());
    }

    @Test
    void unknownKeysCanBeRemovedWithoutAffectingRegistrations() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        AtomicInteger calls = new AtomicInteger();

        registry.registerUnconditional(key("normal"), event -> calls.incrementAndGet());
        registry.registerUnconditionalMonitor(key("monitor"), event -> calls.incrementAndGet());

        registry.unregister(key("missing-normal"));
        registry.unregisterMonitor(key("missing-monitor"));
        assertFalse(registry.isEmpty());
        registry.dispatch(new TestEvent());
        assertEquals(2, calls.get());
    }

    @Test
    void normalHandlersAndMonitorsCanBeRemovedIndependently() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        StringBuilder trace = new StringBuilder();
        CubiKey normal = key("normal");
        CubiKey monitor = key("monitor");

        registry.registerUnconditional(normal, event -> trace.append("normal,"));
        registry.registerUnconditionalMonitor(monitor, event -> trace.append("monitor,"));

        registry.unregister(normal);
        assertFalse(registry.isEmpty());
        registry.dispatch(new TestEvent());
        assertEquals("monitor,", trace.toString());

        registry.unregisterMonitor(monitor);
        assertTrue(registry.isEmpty());
    }

    @Test
    void removingHandlersFromAFifoListPreservesTheRemainingOrder() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        StringBuilder trace = new StringBuilder();
        CubiKey first = key("remove-first");
        CubiKey middle = key("remove-middle");
        CubiKey last = key("remove-last");

        registry.registerUnconditional(first, event -> trace.append("first,"));
        registry.registerUnconditional(middle, event -> trace.append("middle,"));
        registry.registerUnconditional(last, event -> trace.append("last,"));

        registry.unregister(middle);
        registry.dispatch(new TestEvent());
        assertEquals("first,last,", trace.toString());

        trace.setLength(0);
        registry.unregister(first);
        registry.dispatch(new TestEvent());
        assertEquals("last,", trace.toString());

        registry.unregister(last);
        assertTrue(registry.isEmpty());
    }

    @Test
    void mutationsDuringDispatchOnlyAffectLaterDispatches() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        StringBuilder trace = new StringBuilder();
        CubiKey removedKey = key("removed");
        CubiKey addedKey = key("added");
        AtomicBoolean mutated = new AtomicBoolean();

        registry.registerUnconditional(key("mutating"), event -> {
            trace.append("mutating,");
            if (mutated.compareAndSet(false, true)) {
                registry.unregister(removedKey);
                registry.registerUnconditional(addedKey, ignored -> trace.append("added,"));
            }
        });
        registry.registerUnconditional(removedKey, event -> trace.append("removed,"));

        registry.dispatch(new TestEvent());
        assertEquals("mutating,removed,", trace.toString());

        trace.setLength(0);
        registry.dispatch(new TestEvent());
        assertEquals("mutating,added,", trace.toString());
    }

    @Test
    void concurrentUnregistrationDoesNotChangeAnActiveDispatch() throws InterruptedException {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        AtomicBoolean secondHandlerRan = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        CubiKey secondKey = key("second");

        registry.registerUnconditional(key("blocking"), event -> {
            firstHandlerStarted.countDown();
            await(releaseFirstHandler);
        });
        registry.registerUnconditional(secondKey, event -> secondHandlerRan.set(true));

        Thread dispatchThread = Thread.ofPlatform().unstarted(() -> {
            try {
                registry.dispatch(new TestEvent());
            } catch (Throwable throwable) {
                threadFailure.set(throwable);
            }
        });
        dispatchThread.start();

        try {
            assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS), "dispatch did not reach the first handler");
            registry.unregister(secondKey);
        } finally {
            releaseFirstHandler.countDown();
        }

        dispatchThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(dispatchThread.isAlive(), "dispatch thread did not finish");
        assertNull(threadFailure.get());
        assertTrue(secondHandlerRan.get(), "the active snapshot should still contain the removed handler");

        secondHandlerRan.set(false);
        registry.dispatch(new TestEvent());
        assertFalse(secondHandlerRan.get());
    }

    @Test
    void concurrentRegistrationsUsingDistinctKeysAreAllRetained() throws InterruptedException {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            threads[i] = Thread.ofPlatform().unstarted(() -> {
                ready.countDown();
                try {
                    await(start);
                    registry.registerUnconditional(key("concurrent-" + index), event -> calls.incrementAndGet());
                } catch (Throwable throwable) {
                    threadFailure.compareAndSet(null, throwable);
                }
            });
            threads[i].start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "registration threads did not become ready");
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(thread.isAlive(), "registration thread did not finish");
        }

        assertNull(threadFailure.get());
        registry.dispatch(new TestEvent());
        assertEquals(threadCount, calls.get());
    }

    protected static CubiKey key(String value) {
        return CubiKey.from(AbstractCancellableEventRegistryContractTest.class, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("timed out waiting for coordinated test action");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    protected static final class TestEvent extends CancellableEvent {
        private int value;
    }

    private static final class ExpectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
