/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.openloadflow.network.LfBus;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class RemoteVoltageTargetCheckResult {

    private final List<Pair<LfBus, LfBus>> incompatibleTargetControlledBuses = new ArrayList<>();

    private final List<LfBus> unrealisticTargetControllerBuses = new ArrayList<>();

    public List<Pair<LfBus, LfBus>> getIncompatibleTargetControlledBuses() {
        return incompatibleTargetControlledBuses;
    }

    public List<LfBus> getUnrealisticTargetControllerBuses() {
        return unrealisticTargetControllerBuses;
    }
}
