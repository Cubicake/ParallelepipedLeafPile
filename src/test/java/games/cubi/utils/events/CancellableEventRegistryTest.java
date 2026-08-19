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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableEventRegistryTest {
    private static final class TestEvent extends CancellableEvent {}

    @Test
    void emptyRegistryDoesNotCreateLazyEvent() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
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
    }

    @Test
    void dispatchesInPriorityAndRegistrationOrder() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditional(CancellableEventRegistry.Order.LATE, event -> trace.append("late,"));
        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, event -> trace.append("early-1,"));
        registry.registerUnconditional(CancellableEventRegistry.Order.MONITOR, event -> trace.append("monitor,"));
        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, event -> trace.append("normal,"));
        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, event -> trace.append("early-2,"));

        assertTrue(registry.dispatch(new TestEvent()));
        assertEquals("early-1,early-2,normal,late,monitor,", trace.toString());
    }

    @Test
    void populatedRegistryCreatesOneLazyEvent() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        int[] eventsCreated = {0};
        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, event -> {});

        assertTrue(registry.dispatch(() -> {
            eventsCreated[0]++;
            return new TestEvent();
        }));
        assertEquals(1, eventsCreated[0]);
    }

    @Test
    void skipsConditionalHandlersAfterCancellationButRunsUnconditionalHandlers() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, event -> {
            trace.append("cancel,");
            event.cancel();
        });
        registry.register(CancellableEventRegistry.Order.NORMAL, event -> trace.append("conditional,"));
        registry.registerUnconditional(CancellableEventRegistry.Order.LATE, event -> trace.append("unconditional,"));

        assertFalse(registry.dispatch(new TestEvent()));
        assertEquals("cancel,unconditional,", trace.toString());
    }

    @Test
    void monitorCancellationChangesAreRestoredBetweenHandlers() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        AtomicBoolean secondMonitorSawCancellation = new AtomicBoolean();

        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, CancellableEvent::cancel);
        registry.registerUnconditional(CancellableEventRegistry.Order.MONITOR, event -> event.setCancelled(false));
        registry.registerUnconditional(CancellableEventRegistry.Order.MONITOR, event -> secondMonitorSawCancellation.set(event.isCancelled()));

        TestEvent event = new TestEvent();
        assertFalse(registry.dispatch(event));
        assertTrue(event.isCancelled());
        assertTrue(secondMonitorSawCancellation.get());
    }

    @Test
    void monitorCancellationIsRestoredWhenHandlerThrows() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, CancellableEvent::cancel);
        registry.registerUnconditional(CancellableEventRegistry.Order.MONITOR, event -> {
            event.setCancelled(false);
            throw new ExpectedException();
        });

        TestEvent event = new TestEvent();
        assertThrows(ExpectedException.class, () -> registry.dispatch(event));
        assertTrue(event.isCancelled());
    }

    @Test
    void unregisterUsesIdentityWithinTheSelectedPriority() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        StringBuilder trace = new StringBuilder();
        CancellableEventRegistry.BaseEventHandler<TestEvent> handler = event -> trace.append("called,");

        registry.registerUnconditional(CancellableEventRegistry.Order.EARLY, handler);
        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, handler);
        registry.unregister(CancellableEventRegistry.Order.EARLY, handler);

        registry.dispatch(new TestEvent());
        assertEquals("called,", trace.toString());

        registry.unregister(CancellableEventRegistry.Order.EARLY, handler);
        registry.unregister(CancellableEventRegistry.Order.NORMAL, handler);
        assertTrue(registry.isEmpty());
    }

    @Test
    void mutationsDuringDispatchAffectTheNextSnapshot() {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        StringBuilder trace = new StringBuilder();
        CancellableEventRegistry.BaseEventHandler<TestEvent> added = event -> trace.append("added,");
        CancellableEventRegistry.BaseEventHandler<TestEvent> registering = new CancellableEventRegistry.BaseEventHandler<>() {
            private boolean registered;

            @Override
            public void handle(TestEvent event) {
                trace.append("original,");
                if (!registered) {
                    registered = true;
                    registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, added);
                }
            }
        };
        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, registering);

        registry.dispatch(new TestEvent());
        assertEquals("original,", trace.toString());

        trace.setLength(0);
        registry.dispatch(new TestEvent());
        assertEquals("original,added,", trace.toString());
    }

    @Test
    void concurrentUnregistrationDoesNotChangeAnActiveSnapshot() throws InterruptedException {
        CancellableEventRegistry<TestEvent> registry = new CancellableEventRegistry<>();
        CountDownLatch firstHandlerStarted = new CountDownLatch(1);
        CountDownLatch allowFirstHandlerToFinish = new CountDownLatch(1);
        AtomicBoolean secondHandlerRan = new AtomicBoolean();
        CancellableEventRegistry.BaseEventHandler<TestEvent> secondHandler = event -> secondHandlerRan.set(true);

        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, event -> {
            firstHandlerStarted.countDown();
            try {
                assertTrue(allowFirstHandlerToFinish.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        registry.registerUnconditional(CancellableEventRegistry.Order.NORMAL, secondHandler);

        Thread dispatchThread = Thread.ofPlatform().start(() -> registry.dispatch(new TestEvent()));
        assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS));
        registry.unregister(CancellableEventRegistry.Order.NORMAL, secondHandler);
        allowFirstHandlerToFinish.countDown();
        dispatchThread.join();

        assertTrue(secondHandlerRan.get());
        secondHandlerRan.set(false);
        registry.dispatch(new TestEvent());
        assertFalse(secondHandlerRan.get());
    }

    private static final class ExpectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
