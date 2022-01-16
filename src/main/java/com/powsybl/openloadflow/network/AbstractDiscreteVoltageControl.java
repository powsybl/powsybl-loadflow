/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import java.util.Objects;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
abstract class AbstractDiscreteVoltageControl implements DiscreteVoltageControl {

    private final LfBus controlled;

    private final double targetValue;

    protected AbstractDiscreteVoltageControl(LfBus controlled, double targetValue) {
        this.controlled = Objects.requireNonNull(controlled);
        this.targetValue = targetValue;
    }

    @Override
    public double getTargetValue() {
        return targetValue;
    }

    @Override
    public LfBus getControlled() {
        return controlled;
    }
}
