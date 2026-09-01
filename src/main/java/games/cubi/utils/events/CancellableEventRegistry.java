/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import java.util.Objects;

/**
 * Note that if an event handler throws, the throwable will be propagated out of the event system
 * and prevent all future event handlers from receiving that specific event object. This is a deliberate
 * fast-fail design decision.
 */
public sealed class CancellableEventRegistry<E extends CancellableEvent> extends EventRegistry<E> permits OrderedCancellableEventRegistry {

    CancellableEventRegistry(boolean constructHandlerKeyStore) {
        super(constructHandlerKeyStore);
    }

    public CancellableEventRegistry() {
        this(true);
    }
    /**
     * At least one of {@code handlers} or {@code monitors} must not be null
     */
    @Override
    boolean dispatchToHandlers(E event, CancellableEventRegistry.BaseEventHandler<E>[] handlers, CancellableEventRegistry.BaseEventHandler<E>[] monitors) {
        if (handlers == null) return dispatchMonitors(event, monitors, event.isCancelled());
        for (CancellableEventRegistry.BaseEventHandler<E> handler : handlers) {
            handler.handle(event);
        }
        boolean cancelled = event.isCancelled();
        if (monitors != null) dispatchMonitors(event, monitors, cancelled);
        return !cancelled;
    }

    @Override
    boolean dispatchMonitors(E event, CancellableEventRegistry.BaseEventHandler<E>[] monitors, final boolean cancelled) {
        try {
            for (CancellableEventRegistry.BaseEventHandler<E> monitor : monitors) {
                monitor.handle(event);
                event.setCancelled(cancelled);
            }
        } finally {
            event.setCancelled(cancelled);
        }
        return !cancelled;
    }

    public synchronized void registerConditional(CubiKey key, CancellableEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.HANDLER, key, handler);
        registerToStore(HANDLERS_STORE, handler);
    }

    public synchronized void registerConditionalMonitor(CubiKey key, CancellableEventHandler<E> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(key, "Key cannot be null");
        storeKeyTo(Target.MONITOR, key, handler);
        registerToStore(MONITORS_STORE, handler);
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
