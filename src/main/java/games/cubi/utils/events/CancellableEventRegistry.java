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
import java.util.function.Supplier;

public final class CancellableEventRegistry<E extends CancellableEvent> {
    private record Handlers<E extends CancellableEvent>(BaseEventHandler<E>[] eventHandlers, int earlyEnd, int normalEnd, int lateEnd) {}

    private static final BaseEventHandler<?>[] EMPTY_HANDLERS = new BaseEventHandler<?>[0];

    private volatile Handlers<E> handlersStore = new Handlers<>(emptyHandlers(), 0, 0, 0);
    private static final VarHandle HANDLERS = ConcurrentUtil.getVarHandle(CancellableEventRegistry.class, "handlersStore", Handlers.class);

    @SuppressWarnings("unchecked")
    private static <E extends CancellableEvent> BaseEventHandler<E>[] emptyHandlers() {
        return (BaseEventHandler<E>[]) EMPTY_HANDLERS;
    }

    /**
     * For use when allocating an event can be avoided
     * @return false if the event was cancelled
     */
    @SuppressWarnings("unchecked")
    public boolean dispatch(Supplier<E> eventSupplier) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.getAcquire(this);
        if (handlers.eventHandlers.length == 0) {
            return true;
        }
        return dispatchToHandlers(eventSupplier.get(), handlers);
    }

    @SuppressWarnings("unchecked")
    public boolean dispatch(E event) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.getAcquire(this);
        if (handlers.eventHandlers.length == 0) {
            return !event.isCancelled(); //in case an event is pre-cancelled for some reason
        }
        return dispatchToHandlers(event, handlers);
    }

    private boolean dispatchToHandlers(E event, Handlers<E> handlers) {
        BaseEventHandler<E>[] eventHandlers = handlers.eventHandlers;
        for (int i = 0; i < handlers.lateEnd; i++) { //early and normal are before late so this hits them first
            eventHandlers[i].handle(event);
        }
        boolean cancelled = event.isCancelled();
        for (int i = handlers.lateEnd; i < eventHandlers.length; i++) {
            eventHandlers[i].handle(event);
            event.setCancelled(cancelled);
        }
        return !cancelled;
    }

    @SuppressWarnings("unchecked")
    public boolean isEmpty() {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.getAcquire(this);
        return handlers.eventHandlers.length == 0;
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
        int insertionIndex;
        int earlyEnd = handlers.earlyEnd;
        int normalEnd = handlers.normalEnd;
        int lateEnd = handlers.lateEnd;

        switch (order) {
            case EARLY -> {
                insertionIndex = earlyEnd;
                earlyEnd++;
                normalEnd++;
                lateEnd++;
            }
            case NORMAL -> {
                insertionIndex = normalEnd;
                normalEnd++;
                lateEnd++;
            }
            case LATE -> {
                insertionIndex = lateEnd;
                lateEnd++;
            }
            case MONITOR -> insertionIndex = handlers.eventHandlers.length;
            default -> throw new AssertionError(order);
        }

        BaseEventHandler<E>[] updatedHandlers = insert(handlers.eventHandlers, insertionIndex, handler);
        HANDLERS.setRelease(this, new Handlers<>(updatedHandlers, earlyEnd, normalEnd, lateEnd));
    }

    public void register(Order order, CancellableEventHandler<E> handler) {
        registerUnconditional(order, handler);
    }

    @SuppressWarnings("unchecked")
    public synchronized void unregister(Order order, BaseEventHandler<E> handler) {
        Handlers<E> handlers = (Handlers<E>) HANDLERS.get(this); //ordered by the synchronisation, plain reads fine
        int fromIndex;
        int toIndex;

        switch (order) {
            case EARLY -> {
                fromIndex = 0;
                toIndex = handlers.earlyEnd;
            }
            case NORMAL -> {
                fromIndex = handlers.earlyEnd;
                toIndex = handlers.normalEnd;
            }
            case LATE -> {
                fromIndex = handlers.normalEnd;
                toIndex = handlers.lateEnd;
            }
            case MONITOR -> {
                fromIndex = handlers.lateEnd;
                toIndex = handlers.eventHandlers.length;
            }
            default -> throw new AssertionError(order);
        }

        int handlerIndex = identityIndexOf(handlers.eventHandlers, handler, fromIndex, toIndex);
        if (handlerIndex < 0) {
            return;
        }

        int earlyEnd = handlers.earlyEnd;
        int normalEnd = handlers.normalEnd;
        int lateEnd = handlers.lateEnd;
        switch (order) {
            case EARLY -> {
                earlyEnd--;
                normalEnd--;
                lateEnd--;
            }
            case NORMAL -> {
                normalEnd--;
                lateEnd--;
            }
            case LATE -> lateEnd--;
            case MONITOR -> { }
        }

        BaseEventHandler<E>[] updatedHandlers = remove(handlers.eventHandlers, handlerIndex);
        HANDLERS.setRelease(this, new Handlers<>(updatedHandlers, earlyEnd, normalEnd, lateEnd));
    }

    private static <E extends CancellableEvent> BaseEventHandler<E>[] insert(BaseEventHandler<E>[] handlers, int index, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] result = Arrays.copyOf(handlers, handlers.length + 1);
        System.arraycopy(handlers, index, result, index + 1, handlers.length - index);
        result[index] = handler;
        return result;
    }

    private static <E extends CancellableEvent> BaseEventHandler<E>[] remove(BaseEventHandler<E>[] handlers, int index) {
        if (handlers.length == 1) {
            return emptyHandlers();
        }
        BaseEventHandler<E>[] result = Arrays.copyOf(handlers, handlers.length - 1);
        System.arraycopy(handlers, index + 1, result, index, handlers.length - index - 1);
        return result;
    }

    private static <E extends CancellableEvent> int identityIndexOf(BaseEventHandler<E>[] handlers, BaseEventHandler<E> handler, int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++) {
            if (handlers[i] == handler) {
                return i;
            }
        }
        return -1;
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
