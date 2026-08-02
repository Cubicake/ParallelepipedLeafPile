/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.utils.sets;

import java.util.function.IntConsumer;

/**
 * Thread-safe int sets. Hashcode and equals are not implemented.
 */
public interface CopyOnWriteMTIntSet {
    boolean contains(int value);

    void add(int value);

    /**
     * @return whether the value was removed
     */
    boolean remove(int value);

    /**
     * Traverses one immutable snapshot of the set. The order in which elements are supplied to the consumer is undefined.
     */
    void forEach(IntConsumer consumer);

    static CopyOnWriteMTIntSet get() {
        return new SortedStripedMTIntSet();
    }
}
