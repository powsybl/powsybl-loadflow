/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.lf.AbstractEquationSystemUpdater;
import com.powsybl.openloadflow.network.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcEquationSystemUpdater extends AbstractEquationSystemUpdater<AcVariableType, AcEquationType> {

    public AcEquationSystemUpdater(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        super(equationSystem, false);
    }

    @Override
    public void onVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
        controllerBus.getVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateGeneratorVoltageControl(voltageControl, equationSystem));
        controllerBus.getReactivePowerControl().ifPresent(reactivePowerControl -> AcEquationSystemCreator.updateReactivePowerControlBranchEquations(reactivePowerControl, equationSystem));
    }

    @Override
    public void onTransformerPhaseControlChange(LfBranch branch, boolean phaseControlEnabled) {
        AcEquationSystemCreator.updateTransformerPhaseControlEquations(branch.getDiscretePhaseControl().orElseThrow(), equationSystem);
    }

    @Override
    public void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled) {
        AcEquationSystemCreator.updateTransformerVoltageControlEquations(controllerBranch.getVoltageControl().orElseThrow(), equationSystem);
    }

    @Override
    public void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled) {
        AcEquationSystemCreator.updateShuntVoltageControlEquations(controllerShunt.getVoltageControl().orElseThrow(), equationSystem);
    }

    @Override
    protected void updateNonImpedantBranchEquations(LfBranch branch, boolean enable) {
        // depending on the switch status, we activate either v1 = v2, ph1 = ph2 equations
        // or equations that set dummy p and q variable to zero
        equationSystem.getEquation(branch.getNum(), AcEquationType.ZERO_PHI)
                .orElseThrow()
                .setActive(enable);
        equationSystem.getEquation(branch.getNum(), AcEquationType.DUMMY_TARGET_P)
                .orElseThrow()
                .setActive(!enable);

        equationSystem.getEquation(branch.getNum(), AcEquationType.ZERO_V)
                .orElseThrow()
                .setActive(enable);
        equationSystem.getEquation(branch.getNum(), AcEquationType.DUMMY_TARGET_Q)
                .orElseThrow()
                .setActive(!enable);
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        updateElementEquations(element, !disabled);
        switch (element.getType()) {
            case BUS:
                LfBus bus = (LfBus) element;
                checkSlackBus(bus, disabled);
                equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_PHI)
                        .ifPresent(eq -> eq.setActive(!bus.isDisabled()));
                equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_P)
                        .ifPresent(eq -> eq.setActive(!bus.isDisabled() && !bus.isSlack()));
                // set voltage target equation inactive, various voltage control will set next to the correct value
                equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_V)
                        .orElseThrow()
                        .setActive(false);
                bus.getVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateGeneratorVoltageControl(voltageControl, equationSystem));
                bus.getTransformerVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateTransformerVoltageControlEquations(voltageControl, equationSystem));
                bus.getShuntVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateShuntVoltageControlEquations(voltageControl, equationSystem));
                bus.getReactivePowerControl().ifPresent(reactivePowerControl -> AcEquationSystemCreator.updateReactivePowerControlBranchEquations(reactivePowerControl, equationSystem));
                break;
            case BRANCH:
                LfBranch branch = (LfBranch) element;
                branch.getVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateTransformerVoltageControlEquations(voltageControl, equationSystem));
                branch.getDiscretePhaseControl().ifPresent(phaseControl -> AcEquationSystemCreator.updateTransformerPhaseControlEquations(phaseControl, equationSystem));
                branch.getReactivePowerControl().ifPresent(reactivePowerControl -> AcEquationSystemCreator.updateReactivePowerControlBranchEquations(reactivePowerControl, equationSystem));
                break;
            case SHUNT_COMPENSATOR:
                LfShunt shunt = (LfShunt) element;
                shunt.getVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateShuntVoltageControlEquations(voltageControl, equationSystem));
                break;
            case HVDC:
                // nothing to do
                break;
            default:
                throw new IllegalStateException("Unknown element type: " + element.getType());
        }
    }
}
