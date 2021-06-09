/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcEquationSystemUpdater extends AbstractLfNetworkListener {

    private final EquationSystem equationSystem;

    private final VariableSet variableSet;

    private final AcEquationSystemCreationParameters creationParameters;

    public AcEquationSystemUpdater(EquationSystem equationSystem, VariableSet variableSet, AcEquationSystemCreationParameters creationParameters) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.variableSet = Objects.requireNonNull(variableSet);
        this.creationParameters = Objects.requireNonNull(creationParameters);
    }

    private void updateControlledBus(VoltageControl voltageControl, EquationSystem equationSystem, VariableSet variableSet) {

        LfBus controlledBus = voltageControl.getControlledBus();
        Set<LfBus> controllerBuses = voltageControl.getControllerBuses();

        // clean reactive power distribution equations
        controllerBuses.forEach(b -> equationSystem.removeEquation(b.getNum(), EquationType.ZERO_Q));

        // controlled bus has a voltage equation only if one of the controller bus has voltage control on
        List<LfBus> controllerBusesWithVoltageControlOn = controllerBuses.stream()
            .filter(LfBus::isVoltageControllerEnabled)
            .collect(Collectors.toList());
        equationSystem.createEquation(controlledBus.getNum(), EquationType.BUS_V).setActive(!controllerBusesWithVoltageControlOn.isEmpty());

        // create reactive power equations on controller buses that have voltage control on
        if (!controllerBusesWithVoltageControlOn.isEmpty()) {
            AcEquationSystem.createReactivePowerDistributionEquations(equationSystem, variableSet, creationParameters, controllerBusesWithVoltageControlOn);
        }
    }

    @Override
    public void onVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
        if (newVoltageControllerEnabled) { // switch PQ/PV
            Equation qEq = equationSystem.createEquation(controllerBus.getNum(), EquationType.BUS_Q);
            qEq.setActive(false);

            Optional<VoltageControl> vc = controllerBus.getVoltageControl();
            if (vc.isPresent() && controllerBus.isVoltageControllerEnabled()) {
                updateControlledBus(vc.get(), equationSystem, variableSet);
            }
        } else { // switch PV/PQ
            Equation qEq = equationSystem.createEquation(controllerBus.getNum(), EquationType.BUS_Q);
            qEq.setActive(true);

            Optional<VoltageControl> vc = controllerBus.getVoltageControl();
            if (vc.isPresent() && controllerBus.hasVoltageControllerCapability()) {
                updateControlledBus(vc.get(), equationSystem, variableSet);
            }
        }
    }

    @Override
    public void onPhaseControlModeChange(DiscretePhaseControl phaseControl, DiscretePhaseControl.Mode oldMode, DiscretePhaseControl.Mode newMode) {
        boolean on = newMode != DiscretePhaseControl.Mode.OFF;

        // activate/de-activate phase control equation
        equationSystem.createEquation(phaseControl.getControlled().getNum(), EquationType.BRANCH_P)
                .setActive(on);

        // de-activate/activate constant A1 equation
        equationSystem.createEquation(phaseControl.getController().getNum(), EquationType.BRANCH_ALPHA1)
                .setActive(!on);
    }

    @Override
    public void onDiscreteVoltageControlChange(LfBranch controllerBranch, boolean newDiscreteVoltageControllerEnabled) {
        if (newDiscreteVoltageControllerEnabled) {
            // de-activate constant R1 equation
            equationSystem.createEquation(controllerBranch.getNum(), EquationType.BRANCH_RHO1)
                .setActive(false);

            // update transformer distribution equations
            List<LfBranch> firstControllerBranches = controllerBranch.getDiscreteVoltageControl().getControllers().stream()
                .filter(LfBranch::isDiscreteVoltageControllerEnabled)
                .limit(2)
                .collect(Collectors.toList());
            LfBranch firstControllerBranch = firstControllerBranches.get(0); // always exists as controllerBranch is a branch with discrete voltage control enabled
            if (controllerBranch != firstControllerBranch) {
                AcEquationSystem.createR1DisctributionEquation(equationSystem, variableSet, firstControllerBranch, controllerBranch);
            } else if (firstControllerBranches.size() > 1) {
                LfBranch secondControllerBranch = firstControllerBranches.get(1); // was previously first
                AcEquationSystem.createR1DisctributionEquation(equationSystem, variableSet, secondControllerBranch, controllerBranch);
            }

        } else {
            // activate constant R1 equation
            equationSystem.createEquation(controllerBranch.getNum(), EquationType.BRANCH_RHO1)
                .setActive(true);

            // update transformer distribution equations
            Equation zero = equationSystem.removeEquation(controllerBranch.getNum(), EquationType.ZERO_RHO1);
            if (zero == null) { // it was thus the first branch in controllers with discrete voltage control enabled
                Optional<LfBranch> firstController = controllerBranch.getDiscreteVoltageControl().getControllers().stream()
                    .filter(LfBranch::isDiscreteVoltageControllerEnabled)
                    .findFirst();
                if (firstController.isPresent()) {
                    equationSystem.removeEquation(firstController.get().getNum(), EquationType.ZERO_RHO1);
                } else { // no controllers enabled on this discrete voltage control, thus controlled bus not controlled anymore
                    // TODO: I don't think it is possible to end up here... and remove it?
                    LfBus controlledBus = controllerBranch.getDiscreteVoltageControl().getControlled();
                    equationSystem.createEquation(controlledBus.getNum(), EquationType.BUS_V)
                        .setActive(false);
                }
            }
        }
    }

    @Override
    public void onVoltageControlModeChange(DiscreteVoltageControl voltageControl, DiscreteVoltageControl.Mode oldMode, DiscreteVoltageControl.Mode newMode) {
        LfBus bus = voltageControl.getControlled();
        if (newMode == DiscreteVoltageControl.Mode.OFF) {

            // de-activate transformer voltage control equation
            equationSystem.createEquation(bus.getNum(), EquationType.BUS_V)
                    .setActive(false);

            for (LfBranch controllerBranch : bus.getDiscreteVoltageControl().getControllers()) {
                // activate constant R1 equation
                equationSystem.createEquation(controllerBranch.getNum(), EquationType.BRANCH_RHO1)
                        .setActive(true);

                // clean transformer distribution equations
                equationSystem.removeEquation(controllerBranch.getNum(), EquationType.ZERO_RHO1);
            }
        } else { // newMode == DiscreteVoltageControl.Mode.VOLTAGE

            // activate transformer voltage control equation
            equationSystem.createEquation(bus.getNum(), EquationType.BUS_V)
                    .setActive(true);

            // add transformer distribution equations
            AcEquationSystem.createR1DistributionEquations(equationSystem, variableSet, bus.getDiscreteVoltageControl().getControllers());

            for (LfBranch controllerBranch : bus.getDiscreteVoltageControl().getControllers()) {
                // de-activate constant R1 equation
                equationSystem.createEquation(controllerBranch.getNum(), EquationType.BRANCH_RHO1)
                        .setActive(false);
            }
        }
    }
}
