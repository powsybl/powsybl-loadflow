/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
public class TransformerVoltageControl extends DiscreteVoltageControl<LfBranch> {

    public TransformerVoltageControl(LfBus controlledBus, double targetValue, Double targetDeadband) {
        super(controlledBus, targetValue, targetDeadband);
    }

    @Override
    public boolean isControllerEnabled(LfBranch controllerElement) {
        return controllerElement.isVoltageControlEnabled();
    }
}
