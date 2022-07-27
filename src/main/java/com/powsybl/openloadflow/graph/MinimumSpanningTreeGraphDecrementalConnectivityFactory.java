/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.graph;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class MinimumSpanningTreeGraphDecrementalConnectivityFactory<V, E> implements GraphDecrementalConnectivityFactory<V, E> {

    @Override
    public GraphConnectivity<V, E> create() {
        return new MinimumSpanningTreeGraphConnectivity<>();
    }
}
