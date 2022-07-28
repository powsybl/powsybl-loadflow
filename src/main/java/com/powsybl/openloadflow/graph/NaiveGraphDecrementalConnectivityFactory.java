/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.graph;

import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class NaiveGraphDecrementalConnectivityFactory<V, E> implements GraphDecrementalConnectivityFactory<V, E> {

    private final ToIntFunction<V> numGetter;

    public NaiveGraphDecrementalConnectivityFactory(ToIntFunction<V> numGetter) {
        this.numGetter = Objects.requireNonNull(numGetter);
    }

    @Override
    public GraphDecrementalConnectivity<V, E> create() {
        return new NaiveGraphDecrementalConnectivity<>(numGetter);
    }
}
