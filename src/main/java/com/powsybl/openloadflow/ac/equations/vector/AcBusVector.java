/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfBus;

import java.util.List;

/**
 * Vectorized view of the buses. Only variables related the buses at the moment.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcBusVector {

    public final int[] vRow;
    public final int[] phRow;

    public AcBusVector(List<LfBus> buses) {
        vRow = new int[buses.size()];
        phRow = new int[buses.size()];
    }
}
