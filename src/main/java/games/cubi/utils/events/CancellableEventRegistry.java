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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Note that if an event handler throws, the throwable will be propagated out of the event system
 * and prevent all future event handlers from receiving that specific event object. This is a deliberate
 * fast-fail design decision.
 */
public sealed class CancellableEventRegistry<E extends CancellableEvent> permits OrderedCancellableEventRegistry {

    private volatile BaseEventHandler<E>[] handlersStore = null; private static final VarHandle HANDLERS_STORE = ConcurrentUtil.getVarHandle(CancellableEventRegistry.class, "handlersStore", BaseEventHandler[].class);
    private volatile BaseEventHandler<E>[] monitorsStore = null; private static final VarHandle MONITORS_STORE = ConcurrentUtil.getVarHandle(CancellableEventRegistry.class, "monitorsStore", BaseEventHandler[].class);

    private final Map<CubiKey, BaseEventHandler<E>> monitorKeys = new HashMap<>(0, 1);
    private final Map<CubiKey, BaseEventHandler<E>> handlerKeys;

    protected CancellableEventRegistry(boolean constructHandlerKeyStore) {
        handlerKeys = constructHandlerKeyStore ? new HashMap<>(0, 1) : null;
    }

    public CancellableEventRegistry() {
        this(true);
    }

    /**
     * For use when allocating an event can be avoided
     * @return false if the event was cancelled
     */
    @SuppressWarnings("unchecked")
    public boolean dispatch(Supplier<E> eventSupplier) {
        BaseEventHandler<E>[] handlers = (BaseEventHandler<E>[]) HANDLERS_STORE.getAcquire(this);
        BaseEventHandler<E>[] monitors = (BaseEventHandler<E>[]) MONITORS_STORE.getAcquire(this);
        if (isEmpty(handlers, monitors)) {
            return true;
        }
        return dispatchToHandlers(eventSupplier.get(), handlers, monitors);
    }

    /**
     * @return false if the event was cancelled
     */
    @SuppressWarnings("unchecked")
    public boolean dispatch(E event) {
        BaseEventHandler<E>[] handlers = (BaseEventHandler<E>[]) HANDLERS_STORE.getAcquire(this);
        BaseEventHandler<E>[] monitors = (BaseEventHandler<E>[]) MONITORS_STORE.getAcquire(this);
        if (isEmpty(handlers, monitors)) {
            return !event.isCancelled(); //in case an event is pre-cancelled for some reason
        }
        return dispatchToHandlers(event, handlers, monitors);
    }

    /**
     * At least one of {@code handlers} or {@code monitors} must not be null
     */
    private boolean dispatchToHandlers(E event, BaseEventHandler<E>[] handlers, BaseEventHandler<E>[] monitors) {
        if (handlers == null) return dispatchMonitors(event, monitors, event.isCancelled());
        for (BaseEventHandler<E> handler : handlers) {
            handler.handle(event);
        }
        boolean cancelled = event.isCancelled();
        if (monitors != null) dispatchMonitors(event, monitors, cancelled);
        return !cancelled;
    }

    private boolean dispatchMonitors(E event, BaseEventHandler<E>[] monitors, final boolean cancelled) {
        try {
            for (BaseEventHandler<E> monitor : monitors) {
                monitor.handle(event);
                event.setCancelled(cancelled);
            }
        } finally {
            event.setCancelled(cancelled);
        }
        return !cancelled;
    }

    @SuppressWarnings("unchecked")
    public boolean isEmpty() {
        BaseEventHandler<E>[] handlers = (BaseEventHandler<E>[]) HANDLERS_STORE.getAcquire(this);
        BaseEventHandler<E>[] monitors = (BaseEventHandler<E>[]) MONITORS_STORE.getAcquire(this);
        return isEmpty(handlers, monitors);
    }

    public boolean isEmpty(BaseEventHandler<E>[] handlers, BaseEventHandler<E>[] monitors) {
        return (handlers == null && monitors == null);
    }

    protected final synchronized void replaceHandlers(BaseEventHandler<E>[] handlers) {
        if (handlers != null && handlers.length == 0) throw new IllegalArgumentException("Cannot insert 0-length handlers");
        HANDLERS_STORE.setRelease(this, handlers);
    }

    @SuppressWarnings("unchecked")
    private synchronized void registerToStore(VarHandle store, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] handlers = (BaseEventHandler<E>[]) store.get(this); //ordered by the synchronisation on this object
        int length = (handlers == null) ? 0 : handlers.length;
        BaseEventHandler<E>[] updated = new BaseEventHandler[length + 1];

        if (length != 0) System.arraycopy(handlers, 0, updated, 0, length);
        updated[length] = handler;
        store.setRelease(this, updated);
    }

    public synchronized void registerUnconditional(CubiKey key, BaseEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.HANDLER, key, handler);
        registerToStore(HANDLERS_STORE, handler);
    }

    public synchronized void registerConditional(CubiKey key, CancellableEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.HANDLER, key, handler);
        registerToStore(HANDLERS_STORE, handler);
    }

    public synchronized void registerUnconditionalMonitor(CubiKey key, BaseEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.MONITOR, key, handler);
        registerToStore(MONITORS_STORE, handler);
    }

    public synchronized void registerConditionalMonitor(CubiKey key, CancellableEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.MONITOR, key, handler);
        registerToStore(MONITORS_STORE, handler);
    }

    enum Target {MONITOR, HANDLER}

    private synchronized void storeKeyTo(Target target, CubiKey key, BaseEventHandler<E> handler) {
        Map<CubiKey, BaseEventHandler<E>> keyStore = (target == Target.HANDLER) ? handlerKeys : monitorKeys;
        if (keyStore.putIfAbsent(key, handler) != null) {
            throw new IllegalArgumentException("Handler already registered for key " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private void unregisterFromStore(VarHandle store, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] handlers = (BaseEventHandler<E>[]) store.get(this); //ordered by the synchronisation, plain reads fine
        if (handlers == null) return;

        int handlerIndex = identityIndexOf(handlers, handler);
        if (handlerIndex < 0) {
            return;
        }

        BaseEventHandler<E>[] updatedHandlers = remove(handlers, handlerIndex);
        store.setRelease(this, updatedHandlers);
    }

    public synchronized void unregister(CubiKey key) {
        Objects.requireNonNull(key, "Cannot unregister null key");
        BaseEventHandler<E> handler = handlerKeys.remove(key);
        if (handler == null) return;
        unregisterFromStore(HANDLERS_STORE, handler);
    }

    public synchronized void unregisterMonitor(CubiKey key) {
        Objects.requireNonNull(key, "Cannot unregister null key");
        BaseEventHandler<E> monitor = monitorKeys.remove(key);
        if (monitor == null) return;
        unregisterFromStore(MONITORS_STORE, monitor);
    }

    private static <E extends CancellableEvent> BaseEventHandler<E>[] insert(BaseEventHandler<E>[] handlers, int index, BaseEventHandler<E> handler) {
        BaseEventHandler<E>[] result = Arrays.copyOf(handlers, handlers.length + 1);
        System.arraycopy(handlers, index, result, index + 1, handlers.length - index);
        result[index] = handler;
        return result;
    }

    private static <E extends CancellableEvent> BaseEventHandler<E>[] remove(BaseEventHandler<E>[] handlers, int index) {
        if (handlers.length == 1) {
            return null;
        }
        BaseEventHandler<E>[] result = Arrays.copyOf(handlers, handlers.length - 1);
        System.arraycopy(handlers, index + 1, result, index, handlers.length - index - 1);
        return result;
    }

    private static <E extends CancellableEvent> int identityIndexOf(BaseEventHandler<E>[] handlers, BaseEventHandler<E> handler) {
        for (int i = 0; i < handlers.length; i++) {
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
