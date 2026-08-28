/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableEventRegistryTest extends AbstractCancellableEventRegistryContractTest {

    @Override
    protected CancellableEventRegistry<TestEvent> createRegistry() {
        return new CancellableEventRegistry<>();
    }

    @Override
    protected Class<? extends RuntimeException> duplicateNormalKeyException() {
        return IllegalArgumentException.class;
    }

    @Test
    void normalAndMonitorRegistrationsWithTheSameKeyCanBeRemovedIndependently() {
        CancellableEventRegistry<TestEvent> registry = createRegistry();
        CubiKey sharedKey = key("shared");
        StringBuilder trace = new StringBuilder();

        registry.registerUnconditional(sharedKey, event -> trace.append("normal,"));
        registry.registerUnconditionalMonitor(sharedKey, event -> trace.append("monitor,"));

        registry.unregister(sharedKey);
        assertFalse(registry.isEmpty());
        registry.dispatch(new TestEvent());
        assertEquals("monitor,", trace.toString());

        registry.unregisterMonitor(sharedKey);
        assertTrue(registry.isEmpty());
    }
}
