/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableType;
import com.powsybl.openloadflow.network.DiscreteVoltageControl;
import com.powsybl.openloadflow.network.LfBus;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class ShuntVoltageControlOuterLoop implements OuterLoop {

    @Override
    public String getType() {
        return "Transformer voltage control";
    }

    @Override
    public OuterLoopStatus check(OuterLoopContext context) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        if (context.getIteration() == 0) {
            for (LfBus bus : context.getNetwork().getBuses()) {
                if (bus.isDiscreteVoltageControlled() && bus.getDiscreteVoltageControl().getMode() == DiscreteVoltageControl.Mode.VOLTAGE_SHUNT) {
                    // de-activate shunt voltage control equation
                    Equation t = context.getEquationSystem().createEquation(bus.getNum(), EquationType.BUS_V);
                    t.setActive(false);

                    // at first iteration all buses with shunts controlling voltage are switched off
                    for (LfBus controllerBus : bus.getDiscreteVoltageControl().getControllerBuses()) {
                        // de-activate b variable for next outer loop run
                        Variable b = context.getVariableSet().getVariable(controllerBus.getNum(), VariableType.BUS_B);
                        b.setActive(false);

                        // TODO: clean shunt distribution equations
                        // context.getEquationSystem().removeEquation(controllerBus.getNum(), EquationType.ZERO_RHO1);

                        // round the b shift to the closest tap
                        // nothing to do
                    }

                    // switch off regulating shunts
                    bus.getDiscreteVoltageControl().setMode(DiscreteVoltageControl.Mode.OFF);

                    // if at least one shunt has been switched off wee need to continue
                    status = OuterLoopStatus.UNSTABLE;
                }
            }
        }
        return status;
    }
}
