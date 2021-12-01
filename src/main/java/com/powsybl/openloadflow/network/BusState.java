/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

/**
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
public class BusState extends BusDcState {

    private final double angle;
    private final double loadTargetQ;
    private final double generationTargetQ;
    private final boolean isVoltageControllerEnabled;
    private final DiscreteVoltageControl.Mode discreteVoltageControlMode;
    private final boolean disabled;

    public BusState(LfBus bus) {
        super(bus);
        this.angle = bus.getAngle();
        this.loadTargetQ = bus.getLoadTargetQ();
        this.generationTargetQ = bus.getGenerationTargetQ();
        this.isVoltageControllerEnabled = bus.isVoltageControllerEnabled();
        discreteVoltageControlMode = bus.getDiscreteVoltageControl().map(DiscreteVoltageControl::getMode).orElse(null);
        this.disabled = bus.isDisabled();
    }

    public void restore() {
        super.restore();
        element.setAngle(angle);
        element.setLoadTargetQ(loadTargetQ);
        element.setGenerationTargetQ(generationTargetQ);
        element.setVoltageControllerEnabled(isVoltageControllerEnabled);
        element.setVoltageControlSwitchOffCount(0);
        if (discreteVoltageControlMode != null) {
            element.getDiscreteVoltageControl().ifPresent(control -> control.setMode(discreteVoltageControlMode));
        }
        element.setDisabled(disabled);
    }

    public static BusState save(LfBus bus) {
        return new BusState(bus);
    }
}
