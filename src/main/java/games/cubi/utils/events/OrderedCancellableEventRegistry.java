/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/* */

/**
 * Adds dependency-based event handler ordering to {@link CancellableEventRegistry} via {@link RegistrationBuilder}.
 * Monitor registration and execution is unchanged. Dependencies are stored even if not yet registered, so
 * registering a handler {@code A} before {@code B} works even if {@code B} is added to the registry after {@code A}.
 * <p>
 * If there are no required ordering constraints, {@code registerFirst} and {@code registerLast} can be used
 * to insert handlers at the start or end of the list. If ordering constraints are placed on these handlers, they
 * may move to elsewhere in the handler call list. Barring constraints, handlers are sorted based on insertion order.
 * For handlers {@code A} and {@code B} with {@code A} added before {@code B}, if both are added using registerLast
 * {@code B} will run last. If both are added using registerFirst {@code B} will run first.
 * <p>
 * This class is responsible for adding sorting and sorted registration methods.
 * The base class remains responsible for storing and dispatching handler snapshots. This class
 * keeps keyed registration metadata, models active {@code before}/{@code after} relationships as a directed graph,
 * topologically sorts that graph, and publishes the resulting normal-handler array to the base registry.
 * Monitors are unordered, so their storage and dispatch are delegated directly to the base class.
 */
public final class OrderedCancellableEventRegistry<E extends CancellableEvent> extends CancellableEventRegistry<E> {

    public OrderedCancellableEventRegistry() {
        super(false);
    }

    /**
     * The persistent description of one event handler (not including monitors). Constraints are retained even
     * when their referenced keys are absent, allowing them to become active if those keys are added later.
     */
    private record RegistrationNode<E extends CancellableEvent>(
            CubiKey key,
            BaseEventHandler<E> handler,
            Set<CubiKey> before,
            Set<CubiKey> after,
            int sequence
    ) {}

    // Source data from which the handler dispatch order is rebuilt after every keyed mutation.
    private final Map<CubiKey, RegistrationNode<E>> handlerRegistrations = new HashMap<>(0, 1);
    // Sequences are used as stable registration-order based tie-breakers for otherwise-unconstrained nodes.
    private int nextEndSequence = 1;
    // Negative sequences place registerFirst handlers ahead of ordinary nodes unless a constraint overrides it.
    private int nextFirstSequence = -1;

    // Unspecified ordering is registered to the end of the list to match CancellableEventRegistry
    @Override
    public void registerUnconditional(CubiKey key, BaseEventHandler<E> handler) {
        registerUnconditionalLast(key, handler);
    }

    @Override
    public void registerConditional(CubiKey key, CancellableEventHandler<E> handler) {
        registerLast(key, handler);
    }

    /**
     * Creates a staged conditional registration. It is not visible to dispatch until
     * {@link RegistrationBuilder#register()} completes successfully.
     * <p>
     * This handler will be skipped if the event is cancelled before it is reached.
     * If that is not desirable, use {@link #addUnconditionalHandler}
     */
    public RegistrationBuilder addHandler(CubiKey key, CancellableEventHandler<E> handler) {
        return addUnconditionalHandler(key, handler);
    }

    /**
     * Creates a staged registration which runs even when the event is already cancelled.
     * It is not visible to dispatch until {@link RegistrationBuilder#register()} completes successfully.
     */
    public RegistrationBuilder addUnconditionalHandler(CubiKey key, BaseEventHandler<E> handler) {
        return new RegistrationBuilder(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(handler, "handler")
        );
    }

    /**
     * Registers a conditional handler at the front of the current handler list.
     * <p>
     * Explicit ordering constraints can still place another handler before it.
     * Future calls to registerFirst/registerUnconditionalFirst will place their handlers before this one.
     */
    public synchronized void registerFirst(CubiKey key, CancellableEventHandler<E> handler) {
        registerDirect(key, handler, allocateFirstSequence());
    }

    /**
     * Registers an unconditional handler at the front of the current handler list.
     * <p>
     * Explicit ordering constraints can still place another handler before it.
     * Future calls to registerFirst/registerUnconditionalFirst will place their handlers before this one.
     */
    public synchronized void registerUnconditionalFirst(CubiKey key, BaseEventHandler<E> handler) {
        registerDirect(key, handler, allocateFirstSequence());
    }

    /**
     * Registers a conditional handler at the current end of the unconstrained sequence order.
     * <p>
     * Explicit ordering constraints can still place another handler after it.
     * Future calls to registerLast/registerUnconditionalLast will place their handlers before this one.
     */
    public synchronized void registerLast(CubiKey key, CancellableEventHandler<E> handler) {
        registerDirect(key, handler, allocateSequence());
    }

    /**
     * Registers an unconditional handler at the current end of the unconstrained sequence order.
     * <p>
     * Explicit ordering constraints can still place another handler after it.
     * Future calls to registerLast/registerUnconditionalLast will place their handlers before this one.     */
    public synchronized void registerUnconditionalLast(CubiKey key, BaseEventHandler<E> handler) {
        registerDirect(key, handler, allocateSequence());
    }

    /**
     * Registers an unordered monitor which always runs after all normal handlers.
     * Cancellation changes made by a monitor are always discarded.
     */
    public synchronized void registerMonitor(CubiKey key, BaseEventHandler<E> handler) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handler, "handler");

        super.registerUnconditionalMonitor(key, handler);
    }

    /**
     * Removes all handlers assigned this key.
     */
    @Override
    public synchronized void unregister(CubiKey key) {
        Objects.requireNonNull(key, "key");

        if (handlerRegistrations.containsKey(key)) {
            Map<CubiKey, RegistrationNode<E>> candidateNormals = new HashMap<>(handlerRegistrations);
            candidateNormals.remove(key);
            //Removing a normal handler rebuilds the graph because its removal may change
            //the dependency state and sequence order of the remaining registrations.
            BaseEventHandler<E>[] candidateHandlers = buildHandlers(candidateNormals);

            handlerRegistrations.remove(key);
            replaceHandlers(candidateHandlers);
        }

        super.unregisterMonitor(key);
    }

    private synchronized void register(RegistrationBuilder builder) {
        if (builder.registered) {
            throw new IllegalStateException("Handler registration has already completed: " + builder.key);
        }
        ensureNormalKeyAvailable(builder.key);

        RegistrationNode<E> node = new RegistrationNode<>(
                builder.key,
                builder.handler,
                Set.copyOf(builder.before),
                Set.copyOf(builder.after),
                allocateSequence()
        );
        Map<CubiKey, RegistrationNode<E>> candidateNormals = new HashMap<>(handlerRegistrations);
        candidateNormals.put(node.key, node);
        BaseEventHandler<E>[] candidateHandlers = buildHandlers(candidateNormals);

        handlerRegistrations.put(node.key, node);
        builder.registered = true;
        replaceHandlers(candidateHandlers);
    }

    // First/last registrations have no explicit graph constraints but use the same transactional rebuild.
    private void registerDirect(CubiKey key, BaseEventHandler<E> handler, int sequence) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handler, "handler");
        ensureNormalKeyAvailable(key);

        RegistrationNode<E> node = new RegistrationNode<>(key, handler, Set.of(), Set.of(), sequence);
        Map<CubiKey, RegistrationNode<E>> candidateNormals = new HashMap<>(handlerRegistrations);
        candidateNormals.put(key, node);
        BaseEventHandler<E>[] candidateHandlers = buildHandlers(candidateNormals);

        handlerRegistrations.put(key, node);
        replaceHandlers(candidateHandlers);
    }

    private void ensureNormalKeyAvailable(CubiKey key) {
        if (handlerRegistrations.containsKey(key)) {
            throw new IllegalStateException("Handler key is already registered: " + key);
        }
    }

    /**
     * Performs a stable Kahn topological sort. Edges whose other endpoint is not currently registered
     * are ignored for this rebuild but remain stored in their registration node for future rebuilds.
     */
    private BaseEventHandler<E>[] buildHandlers(Map<CubiKey, RegistrationNode<E>> registrationNodes) {
        if (registrationNodes.isEmpty()) {
            return null;
        }

        // Build an adjacency list and incoming-edge count for every currently registered key.
        Object2IntOpenHashMap<CubiKey> incomingEdges = new Object2IntOpenHashMap<>(registrationNodes.size());
        Map<CubiKey, Set<CubiKey>> outgoingEdges = new Object2ObjectOpenHashMap<>(registrationNodes.size());
        for (CubiKey key : registrationNodes.keySet()) {
            incomingEdges.put(key, 0);
            outgoingEdges.put(key, new HashSet<>());
        }

        for (RegistrationNode<E> node : registrationNodes.values()) {
            for (CubiKey before : node.before) {
                if (registrationNodes.containsKey(before)) {
                    addEdge(node.key, before, outgoingEdges, incomingEdges);
                }
            }
            for (CubiKey after : node.after) {
                if (registrationNodes.containsKey(after)) {
                    addEdge(after, node.key, outgoingEdges, incomingEdges);
                }
            }
        }

        // Sequence order makes the result deterministic whenever multiple nodes are ready at once.
        PriorityQueue<RegistrationNode<E>> ready = new PriorityQueue<>(Comparator.comparingInt(RegistrationNode::sequence));
        for (RegistrationNode<E> node : registrationNodes.values()) {
            if (incomingEdges.getInt(node.key) == 0) {
                ready.add(node);
            }
        }

        // Repeatedly emit an unconstrained node and release nodes that depended on it.
        List<BaseEventHandler<E>> sortedNormalHandlers = new ArrayList<>(registrationNodes.size());
        while (!ready.isEmpty()) {
            RegistrationNode<E> node = ready.remove();
            sortedNormalHandlers.add(node.handler);

            for (CubiKey target : outgoingEdges.get(node.key)) {
                int remainingEdges = incomingEdges.merge(target, -1, Integer::sum);
                if (remainingEdges == 0) {
                    ready.add(registrationNodes.get(target));
                }
            }
        }

        // Any remaining nodes are part of a cycle or downstream from one, so no complete order exists.
        if (sortedNormalHandlers.size() != registrationNodes.size()) {
            List<CubiKey> blockedKeys = incomingEdges.object2IntEntrySet().stream()
                    .filter(entry -> entry.getIntValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(CubiKey::toString))
                    .toList();
            throw new IllegalStateException("Cyclic event handler ordering prevents registration of: " + blockedKeys);
        }

        BaseEventHandler<E>[] handlers = newHandlerArray(registrationNodes.size());
        int index = 0;
        for (BaseEventHandler<E> handler : sortedNormalHandlers) {
            handlers[index++] = handler;
        }
        return handlers;
    }

    // Sets are used for outgoing edges so repeated before/after declarations do not double-count an edge.
    private static void addEdge(CubiKey from, CubiKey to,
            Map<CubiKey, Set<CubiKey>> outgoingEdges, Object2IntOpenHashMap<CubiKey> incomingEdges) {
        
        if (outgoingEdges.get(from).add(to)) {
            incomingEdges.merge(to, 1, Integer::sum);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends CancellableEvent> BaseEventHandler<E>[] newHandlerArray(int length) {
        return (BaseEventHandler<E>[]) new BaseEventHandler<?>[length];
    }

    private synchronized int allocateSequence() {
        return nextEndSequence++;
    }

    private synchronized int allocateFirstSequence() {
        return nextFirstSequence--;
    }

    /**
     * Collects ordering constraints without exposing a partially configured registration to dispatch.
     * A builder remains retryable when registration fails because a currently active cycle is detected.
     * <p>
     * Registration builders are not designed to be stored, and are not thread-safe.
     */
    public final class RegistrationBuilder {
        private final CubiKey key;
        private final BaseEventHandler<E> handler;
        private final Set<CubiKey> before = new HashSet<>();
        private final Set<CubiKey> after = new HashSet<>();
        private boolean registered;

        private RegistrationBuilder(CubiKey key, BaseEventHandler<E> handler) {
            this.key = key;
            this.handler = handler;
        }

        public RegistrationBuilder before(CubiKey key) {
            ensureNotRegistered();
            ensureNotSelfOrNull(key);
            before.add(key);
            return this;
        }

        public RegistrationBuilder after(CubiKey key) {
            ensureNotRegistered();
            ensureNotSelfOrNull(key);
            after.add(key);
            return this;
        }

        public void register() {
            OrderedCancellableEventRegistry.this.register(this);
        }

        private void ensureNotRegistered() {
            if (registered) {
                throw new IllegalStateException("Handler registration has already completed: " + key);
            }
        }

        private void ensureNotSelfOrNull(CubiKey key) {
            if (this.key.equals(Objects.requireNonNull(key, "Cannot order null CubiKey"))) {
                throw new IllegalArgumentException("Handler cannot be ordered relative to itself: " + key);
            }
        }
    }
}
