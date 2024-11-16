/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.openloadflow.network.LfBus;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class RemoteVoltageTargetCheckResult {

    public record IncompatibleTarget(LfBus controlledBus1, LfBus controlledBus2, double targetVoltagePlausibilityIndicator) {
    }

    public record UnrealisticTarget(LfBus controllerBus, double estimatedDvController) {
    }

    private final List<IncompatibleTarget> incompatibleTargets = new ArrayList<>();

    private final List<UnrealisticTarget> unrealisticTargets = new ArrayList<>();

    public List<IncompatibleTarget> getIncompatibleTargets() {
        return incompatibleTargets;
    }

    public List<UnrealisticTarget> getUnrealisticTargets() {
        return unrealisticTargets;
    }
}
