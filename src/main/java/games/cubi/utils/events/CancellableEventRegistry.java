/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;

import java.lang.invoke.VarHandle;
import java.util.Arrays;

public final class CancellableEventRegistry<E extends CancellableEvent> {
    private record Handlers<E extends CancellableEvent>(BaseEventHandler<E>[] earlyEventHandlers, BaseEventHandler<E>[] normalEventHandlers, BaseEventHandler<E>[] lateEventHandlers, BaseEventHandler<E>[] monitoringEventHandlers) {}

    private volatile Handlers<E> handlersStore = new Handlers<>(emptyHandlers(), emptyHandlers(), emptyHandlers(), emptyHandlers());
    private static final VarHandle HANDLERS = ConcurrentUtil.getVarHandle(CancellableEventRegistry.class, "handlersStore", Handlers.class);

    @SuppressWarnings("unchecked")
    private static <E extends CancellableEvent> BaseEventHandler<E>[] emptyHandlers() {
        return (BaseEventHandler<E>[]) new BaseEventHandler<?>[0];
    }

    @SuppressWarnings("unchecked")
    public boolean dispatch(E event) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.getAcquire(this);
        for (BaseEventHandler<E> eventHandler : handlers.earlyEventHandlers) {
            eventHandler.handle(event);
        }
        for (BaseEventHandler<E> eventHandler : handlers.normalEventHandlers) {
            eventHandler.handle(event);
        }
        for (BaseEventHandler<E> eventHandler : handlers.lateEventHandlers) {
            eventHandler.handle(event);
        }
        boolean cancelled = event.isCancelled();
        for (BaseEventHandler<E> eventHandler : handlers.monitoringEventHandlers) {
            eventHandler.handle(event);
            event.setCancelled(cancelled);
        }
        return !cancelled;
    }

    public enum Order {
        EARLY,
        NORMAL,
        LATE,
        /**
         * Monitor handlers are explicitly forbidden from mutating.
         * Attempting to cancel or uncancel the event here will be undone.
         */
        MONITOR,
    }

    @SuppressWarnings("unchecked")
    public synchronized void registerUnconditional(Order order, BaseEventHandler<E> handler) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.get(this); //ordered by the synchronisation on this object
        switch (order) {
            case EARLY -> HANDLERS.setRelease(this, new Handlers<>(append(handlers.earlyEventHandlers, handler), handlers.normalEventHandlers, handlers.lateEventHandlers, handlers.monitoringEventHandlers));
            case NORMAL -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, append(handlers.normalEventHandlers, handler), handlers.lateEventHandlers, handlers.monitoringEventHandlers));
            case LATE -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, handlers.normalEventHandlers, append(handlers.lateEventHandlers, handler), handlers.monitoringEventHandlers));
            case MONITOR -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, handlers.normalEventHandlers, handlers.lateEventHandlers, append(handlers.monitoringEventHandlers, handler)));
        }
    }

    public synchronized void register(Order order, CancellableEventHandler<E> handler) {
        registerUnconditional(order, handler);
    }

    public synchronized void unregister(Order order, BaseEventHandler<E> handler) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.get(this);
        switch (order) {
            case EARLY -> HANDLERS.setRelease(this, new Handlers<>(remove(handlers.earlyEventHandlers, handler), handlers.normalEventHandlers, handlers.lateEventHandlers, handlers.monitoringEventHandlers));
            case NORMAL -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, remove(handlers.normalEventHandlers, handler), handlers.lateEventHandlers, handlers.monitoringEventHandlers));
            case LATE -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, handlers.normalEventHandlers, remove(handlers.lateEventHandlers, handler), handlers.monitoringEventHandlers));
            case MONITOR -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, handlers.normalEventHandlers, handlers.lateEventHandlers, remove(handlers.monitoringEventHandlers, handler)));
        }
    }

    private BaseEventHandler<E>[] append(BaseEventHandler<E>[] handlers, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] copy = Arrays.copyOf(handlers, handlers.length + 1);

        copy[handlers.length] = handler;
        return copy;
    }

    private BaseEventHandler<E>[] remove(BaseEventHandler<E>[] handlers, BaseEventHandler<E> handler) {
        for (int i = 0; i < handlers.length; i++) {
            if (handlers[i] == handler) {
                BaseEventHandler<E>[] result = Arrays.copyOf(handlers, handlers.length - 1);
                System.arraycopy(handlers, i + 1, result, i, handlers.length - i - 1);
                return result;
            }
        }
        return handlers;
    }

    @FunctionalInterface
    public interface BaseEventHandler<E extends CancellableEvent> {
        void handle(E event);
    }

    @FunctionalInterface
    public interface CancellableEventHandler<E extends CancellableEvent> extends BaseEventHandler<E> {
        default void handle(E event) {
            if (event.isCancelled()) return;
            actualHandler(event);
        }
        void actualHandler(E event);
    }
}
