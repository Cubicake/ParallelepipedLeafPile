/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderedCancellableEventRegistryTest extends AbstractCancellableEventRegistryContractTest {

    @Override
    protected OrderedCancellableEventRegistry<TestEvent> createRegistry() {
        return new OrderedCancellableEventRegistry<>();
    }

    @Override
    protected Class<? extends RuntimeException> duplicateNormalKeyException() {
        return IllegalStateException.class;
    }

    @Test
    void registerFirstUsesLifoPlacementForConditionalAndUnconditionalHandlers() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();

        registry.registerFirst(key("first-a"), conditionalHandler(trace, "a"));
        registry.registerUnconditionalFirst(key("first-b"), handler(trace, "b"));
        registry.registerFirst(key("first-c"), conditionalHandler(trace, "c"));

        registry.dispatch(new TestEvent());
        assertEquals(List.of("c", "b", "a"), trace);
    }

    @Test
    void registerLastUsesFifoPlacementForConditionalAndUnconditionalHandlers() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();

        registry.registerLast(key("last-a"), conditionalHandler(trace, "a"));
        registry.registerUnconditionalLast(key("last-b"), handler(trace, "b"));
        registry.registerLast(key("last-c"), conditionalHandler(trace, "c"));

        registry.dispatch(new TestEvent());
        assertEquals(List.of("a", "b", "c"), trace);
    }

    @Test
    void explicitConstraintCanOverrideFirstPlacement() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey first = key("first");

        registry.registerUnconditionalFirst(first, handler(trace, "first"));
        registry.addUnconditionalHandler(key("constrained"), handler(trace, "constrained"))
                .before(first)
                .register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "first"), trace);
    }

    @Test
    void explicitConstraintCanOverrideLastPlacement() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey last = key("last");

        registry.registerUnconditionalLast(last, handler(trace, "last"));
        registry.addUnconditionalHandler(key("constrained"), handler(trace, "constrained"))
                .before(last)
                .register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "last"), trace);
    }

    @Test
    void beforeAndAfterConstraintsProduceTheRequiredRelativeOrder() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey first = key("first");
        CubiKey last = key("last");

        registry.registerUnconditionalLast(first, handler(trace, "first"));
        registry.registerUnconditionalLast(last, handler(trace, "last"));
        registry.addUnconditionalHandler(key("middle"), handler(trace, "middle"))
                .after(first)
                .before(last)
                .register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("first", "middle", "last"), trace);
    }

    @Test
    void multipleTransitiveConstraintsProduceACompleteValidOrder() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey a = key("a");
        CubiKey b = key("b");
        CubiKey c = key("c");
        CubiKey d = key("d");

        registry.addUnconditionalHandler(a, handler(trace, "a")).before(b).before(c).register();
        registry.addUnconditionalHandler(b, handler(trace, "b")).before(d).register();
        registry.addUnconditionalHandler(c, handler(trace, "c")).before(d).register();
        registry.registerUnconditionalLast(d, handler(trace, "d"));

        registry.dispatch(new TestEvent());
        assertEquals(4, trace.size());
        assertEquals(Set.of("a", "b", "c", "d"), new HashSet<>(trace));
        assertBefore(trace, "a", "b");
        assertBefore(trace, "a", "c");
        assertBefore(trace, "b", "d");
        assertBefore(trace, "c", "d");
    }

    @Test
    void unrelatedHandlersKeepTheirPlacementWhenAConstraintReleasesLater() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey dependency = key("dependency");

        registry.registerUnconditionalLast(key("unrelated-a"), handler(trace, "unrelated-a"));
        registry.addUnconditionalHandler(key("dependent"), handler(trace, "dependent"))
                .after(dependency)
                .register();
        registry.registerUnconditionalLast(key("unrelated-b"), handler(trace, "unrelated-b"));
        registry.registerUnconditionalLast(dependency, handler(trace, "dependency"));

        registry.dispatch(new TestEvent());
        assertEquals(Set.of("unrelated-a", "unrelated-b", "dependency", "dependent"), new HashSet<>(trace));
        assertBefore(trace, "unrelated-a", "unrelated-b");
        assertBefore(trace, "dependency", "dependent");
    }

    @Test
    void repeatedConstraintsDoNotChangeTheResult() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey start = key("start");
        CubiKey end = key("end");

        registry.registerUnconditionalLast(start, handler(trace, "start"));
        registry.registerUnconditionalLast(end, handler(trace, "end"));
        registry.addUnconditionalHandler(key("middle"), handler(trace, "middle"))
                .after(start)
                .after(start)
                .before(end)
                .before(end)
                .register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("start", "middle", "end"), trace);
    }

    @Test
    void absentConstraintTargetActivatesAndReactivatesWhenRegistered() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey constrained = key("constrained");
        CubiKey target = key("target");

        registry.addUnconditionalHandler(constrained, handler(trace, "constrained"))
                .before(target)
                .register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained"), trace);

        trace.clear();
        registry.registerUnconditionalFirst(target, handler(trace, "target"));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "target"), trace);

        trace.clear();
        registry.unregister(target);
        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained"), trace);

        trace.clear();
        registry.registerUnconditionalFirst(target, handler(trace, "target"));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "target"), trace);
    }

    @Test
    void monitorKeyDoesNotSatisfyANormalHandlerConstraint() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey sharedTarget = key("shared-target");

        registry.addUnconditionalHandler(key("constrained"), handler(trace, "constrained"))
                .before(sharedTarget)
                .register();
        registry.registerMonitor(sharedTarget, handler(trace, "monitor"));

        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "monitor"), trace);

        trace.clear();
        registry.registerUnconditionalFirst(sharedTarget, handler(trace, "normal-target"));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("constrained", "normal-target", "monitor"), trace);
    }

    @Test
    void stagedRegistrationIsInvisibleUntilCommitted() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder builder =
                registry.addHandler(key("staged"), conditionalHandler(trace, "staged"));

        assertTrue(registry.isEmpty());
        registry.dispatch(new TestEvent());
        assertTrue(trace.isEmpty());

        builder.register();
        assertFalse(registry.isEmpty());
        registry.dispatch(new TestEvent());
        assertEquals(List.of("staged"), trace);
    }

    @Test
    void builderSupportsChainedBeforeAndAfterDeclarations() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        List<String> trace = new ArrayList<>();
        CubiKey start = key("start");
        CubiKey end = key("end");
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder builder =
                registry.addUnconditionalHandler(key("middle"), handler(trace, "middle"));

        registry.registerUnconditionalLast(start, handler(trace, "start"));
        registry.registerUnconditionalLast(end, handler(trace, "end"));
        assertSame(builder, builder.after(start));
        assertSame(builder, builder.before(end));
        builder.register();

        registry.dispatch(new TestEvent());
        assertEquals(List.of("start", "middle", "end"), trace);
    }

    @Test
    void orderedRegistrationMethodsRejectNullKeysAndHandlers() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey valid = key("valid");

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> registry.registerFirst(null, event -> {})),
                () -> assertThrows(NullPointerException.class, () -> registry.registerFirst(valid, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalFirst(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalFirst(valid, null)),
                () -> assertThrows(NullPointerException.class, () -> registry.registerLast(null, event -> {})),
                () -> assertThrows(NullPointerException.class, () -> registry.registerLast(valid, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalLast(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.registerUnconditionalLast(valid, null)),
                () -> assertThrows(NullPointerException.class, () -> registry.addHandler(null, event -> {})),
                () -> assertThrows(NullPointerException.class, () -> registry.addHandler(valid, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.addUnconditionalHandler(null, event -> {})),
                () -> assertThrows(NullPointerException.class,
                        () -> registry.addUnconditionalHandler(valid, null)),
                () -> assertThrows(NullPointerException.class, () -> registry.registerMonitor(null, event -> {})),
                () -> assertThrows(NullPointerException.class, () -> registry.registerMonitor(valid, null))
        );
        assertTrue(registry.isEmpty());
    }

    @Test
    void builderRejectsNullAndSelfConstraints() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey ownKey = key("own");
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder builder =
                registry.addHandler(ownKey, event -> {});

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> builder.before(null)),
                () -> assertThrows(NullPointerException.class, () -> builder.after(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> builder.before(ownKey)),
                () -> assertThrows(IllegalArgumentException.class, () -> builder.after(ownKey))
        );
        assertTrue(registry.isEmpty());
    }

    @Test
    void completedBuilderCannotBeRegisteredOrModifiedAgain() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder builder =
                registry.addHandler(key("completed"), event -> {});
        builder.register();

        assertAll(
                () -> assertThrows(IllegalStateException.class, builder::register),
                () -> assertThrows(IllegalStateException.class, () -> builder.before(key("other-before"))),
                () -> assertThrows(IllegalStateException.class, () -> builder.after(key("other-after")))
        );
    }

    @Test
    void duplicateBuilderKeyIsRejectedWithoutChangingTheActiveHandler() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey duplicate = key("duplicate-builder");
        List<String> trace = new ArrayList<>();

        registry.registerUnconditionalLast(duplicate, handler(trace, "original"));
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder duplicateBuilder =
                registry.addUnconditionalHandler(duplicate, handler(trace, "replacement"));

        assertThrows(IllegalStateException.class, duplicateBuilder::register);
        registry.dispatch(new TestEvent());
        assertEquals(List.of("original"), trace);
    }

    @Test
    void directCycleIsRejectedTransactionallyAndFailedBuilderCanBeRetried() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey a = key("cycle-a");
        CubiKey b = key("cycle-b");
        List<String> trace = new ArrayList<>();

        registry.addUnconditionalHandler(a, handler(trace, "a")).before(b).register();
        OrderedCancellableEventRegistry<TestEvent>.RegistrationBuilder bBuilder =
                registry.addUnconditionalHandler(b, handler(trace, "b")).before(a);

        assertThrows(IllegalStateException.class, bBuilder::register);
        registry.dispatch(new TestEvent());
        assertEquals(List.of("a"), trace, "a failed registration must not change the active handlers");

        trace.clear();
        registry.unregister(a);
        bBuilder.register();
        registry.dispatch(new TestEvent());
        assertEquals(List.of("b"), trace);
    }

    @Test
    void transitiveCycleIsRejectedWithoutChangingExistingHandlers() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey a = key("transitive-a");
        CubiKey b = key("transitive-b");
        CubiKey c = key("transitive-c");
        List<String> trace = new ArrayList<>();

        registry.addUnconditionalHandler(a, handler(trace, "a")).before(b).register();
        registry.addUnconditionalHandler(b, handler(trace, "b")).before(c).register();

        assertThrows(IllegalStateException.class,
                () -> registry.addUnconditionalHandler(c, handler(trace, "c")).before(a).register());

        registry.dispatch(new TestEvent());
        assertEquals(List.of("a", "b"), trace);
    }

    @Test
    void directPlacementRegistrationThatActivatesACycleIsRejectedTransactionally() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey a = key("latent-cycle-a");
        CubiKey b = key("latent-cycle-b");
        List<String> trace = new ArrayList<>();

        registry.addUnconditionalHandler(a, handler(trace, "a")).before(b).after(b).register();

        assertThrows(IllegalStateException.class,
                () -> registry.registerUnconditionalFirst(b, handler(trace, "b")));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("a"), trace);

        trace.clear();
        registry.unregister(a);
        registry.registerUnconditionalFirst(b, handler(trace, "b"));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("b"), trace);
    }

    @Test
    void removingConstrainedHandlerLeavesAValidOrderForRemainingHandlers() {
        OrderedCancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey a = key("remove-a");
        CubiKey b = key("remove-b");
        CubiKey c = key("remove-c");
        List<String> trace = new ArrayList<>();

        registry.addUnconditionalHandler(a, handler(trace, "a")).before(b).register();
        registry.addUnconditionalHandler(b, handler(trace, "b")).before(c).register();
        registry.registerUnconditionalLast(c, handler(trace, "c"));
        registry.dispatch(new TestEvent());
        assertEquals(List.of("a", "b", "c"), trace);

        trace.clear();
        registry.unregister(b);
        registry.dispatch(new TestEvent());
        assertEquals(List.of("a", "c"), trace);
    }

    @Test
    void unregisterRemovesNormalAndMonitorSharingAKeyWhileMonitorRemovalIsSelective() {
        CubiKey shared = key("shared-removal");
        OrderedCancellableEventRegistry<TestEvent> combinedRemoval = createRegistry();
        List<String> combinedTrace = new ArrayList<>();

        combinedRemoval.registerUnconditionalLast(shared, handler(combinedTrace, "normal"));
        combinedRemoval.registerMonitor(shared, handler(combinedTrace, "monitor"));
        combinedRemoval.unregister(shared);

        assertTrue(combinedRemoval.isEmpty());
        combinedRemoval.dispatch(new TestEvent());
        assertTrue(combinedTrace.isEmpty());

        OrderedCancellableEventRegistry<TestEvent> monitorOnlyRemoval = createRegistry();
        List<String> selectiveTrace = new ArrayList<>();
        monitorOnlyRemoval.registerUnconditionalLast(shared, handler(selectiveTrace, "normal"));
        monitorOnlyRemoval.registerMonitor(shared, handler(selectiveTrace, "monitor"));
        monitorOnlyRemoval.unregisterMonitor(shared);

        assertFalse(monitorOnlyRemoval.isEmpty());
        monitorOnlyRemoval.dispatch(new TestEvent());
        assertEquals(List.of("normal"), selectiveTrace);
    }

    private static CancellableEventRegistry.BaseEventHandler<TestEvent> handler(List<String> trace, String value) {
        return event -> trace.add(value);
    }

    private static CancellableEventRegistry.CancellableEventHandler<TestEvent> conditionalHandler(
            List<String> trace, String value) {
        return event -> trace.add(value);
    }

    private static void assertBefore(List<String> trace, String first, String second) {
        assertTrue(trace.indexOf(first) < trace.indexOf(second),
                () -> first + " should appear before " + second + " in " + trace);
    }
}
