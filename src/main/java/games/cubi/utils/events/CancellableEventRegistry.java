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

public abstract class CancellableEventRegistry<E extends CancellableEvent> {
    private record Handlers<E extends CancellableEvent>(BaseEventHandler<E>[] earlyEventHandlers, BaseEventHandler<E>[] normalEventHandlers, BaseEventHandler<E>[] lateEventHandlers) {}

    private volatile Handlers<E> handlersStore = new Handlers<>(emptyHandlers(), emptyHandlers(), emptyHandlers());
    private static final VarHandle HANDLERS = ConcurrentUtil.getVarHandle(CancellableEventRegistry.class, "handlersStore", Handlers.class);

    protected abstract E newEvent();

    @SuppressWarnings("unchecked")
    private static <E extends CancellableEvent> BaseEventHandler<E>[] emptyHandlers() {
        return (BaseEventHandler<E>[]) new BaseEventHandler<?>[0];
    }

    @SuppressWarnings("unchecked")
    public boolean call() {
        E event = newEvent();
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
        return !event.isCancelled();
    }

    public enum Order {
        EARLY,
        NORMAL,
        LATE,
    }

    @SuppressWarnings("unchecked")
    public synchronized void registerUnconditional(BaseEventHandler<E> handler, Order order) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.get(this); //ordered by the synchronisation on this object
        switch (order) {
            case EARLY -> HANDLERS.setRelease(this, new Handlers<>(append(handlers.earlyEventHandlers, handler), handlers.normalEventHandlers, handlers.lateEventHandlers));
            case NORMAL -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, append(handlers.normalEventHandlers, handler), handlers.lateEventHandlers));
            case LATE -> HANDLERS.setRelease(this, new Handlers<>(handlers.earlyEventHandlers, handlers.normalEventHandlers, append(handlers.lateEventHandlers, handler)));
        }
    }

    public synchronized void register(CancellableEventHandler<E> handler, Order order) {
        registerUnconditional(handler, order);
    }

    private BaseEventHandler<E>[] append(BaseEventHandler<E>[] handlers, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] copy = Arrays.copyOf(handlers, handlers.length + 1);

        copy[handlers.length] = handler;
        return copy;
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
