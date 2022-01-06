/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
public class ShuntVoltageControl extends AbstractDiscreteVoltageControl {

    protected final List<LfBus> controllers = new ArrayList<>();

    public ShuntVoltageControl(LfBus controlled, DiscreteVoltageControl.Mode mode, double targetValue) {
        super(controlled, mode, targetValue);
    }

    public List<LfBus> getControllers() {
        return controllers;
    }

    public void addController(LfBus controllerBus) {
        Objects.requireNonNull(controllerBus);
        controllers.add(controllerBus);
    }
}
