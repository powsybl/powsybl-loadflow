/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.network.*;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcTargetVector extends TargetVector<AcVariableType, AcEquationType> {

    private static double getBusTargetV(LfBus bus) {
        Objects.requireNonNull(bus);
        double targetV = bus.getHighestPriorityTargetV()
                .orElseThrow(() -> new IllegalStateException("No active voltage control has been found for bus '" + bus.getId() + "'"));
        if (bus.hasGeneratorsWithSlope()) {
            // take first generator with slope: network loading ensures that there's only one generator with slope
            double slope = bus.getGeneratorsControllingVoltageWithSlope().get(0).getSlope();
            targetV -= slope * (bus.getLoadTargetQ() - bus.getGenerationTargetQ());
        }
        return targetV;
    }

    private static double getGeneratorReactivePowerDistributionTarget(LfNetwork network, int busNum) {
        LfBus controllerBus = network.getBus(busNum);
        double target = (controllerBus.getRemoteControlReactivePercent() - 1) * controllerBus.getTargetQ();
        List<LfBus> mergedControllerBuses;
        if (controllerBus.getGeneratorVoltageControl().isPresent()) {
            mergedControllerBuses = controllerBus.getGeneratorVoltageControl().orElseThrow().getMergedControllerElements();
        } else if (controllerBus.hasGeneratorReactivePowerControl()) {
            mergedControllerBuses = controllerBus.getGeneratorReactivePowerControl().orElseThrow().getControllerBuses();
        } else {
            throw new PowsyblException("Controller bus '" + controllerBus.getId() + "' has no voltage or reactive remote control");
        }
        return updateReactivePowerDistributionTarget(target, controllerBus, mergedControllerBuses);
    }

    private static double updateReactivePowerDistributionTarget(double target, LfBus controllerBus, List<LfBus> controllerBuses) {
        double updatedTarget = target;
        for (LfBus otherControllerBus : controllerBuses) {
            if (otherControllerBus != controllerBus) {
                updatedTarget += controllerBus.getRemoteControlReactivePercent() * otherControllerBus.getTargetQ();
            }
        }
        return updatedTarget;
    }

    private static double getReactivePowerControlTarget(LfBranch branch) {
        Objects.requireNonNull(branch);
        return branch.getGeneratorReactivePowerControl().map(GeneratorReactivePowerControl::getTargetValue)
                .orElseThrow(() -> new PowsyblException("Branch '" + branch.getId() + "' has no target in for generator reactive remote control"));
    }

    public static void init(Equation<AcVariableType, AcEquationType> equation, LfNetwork network, double[] targets) {
        switch (equation.getType()) {
            case BUS_TARGET_P :
                targets[equation.getColumn()] = network.getBus(equation.getElementNum()).getTargetP();
                break;

            case BUS_DISTR_SLACK_P :
                targets[equation.getColumn()] = network.getBus(equation.getElementNum()).getTargetP() - network.getSlackBuses().get(0).getTargetP();
                break;

            case BUS_TARGET_Q:
                targets[equation.getColumn()] = network.getBus(equation.getElementNum()).getTargetQ();
                break;

            case BUS_TARGET_V:
                targets[equation.getColumn()] = getBusTargetV(network.getBus(equation.getElementNum()));
                break;

            case BUS_TARGET_PHI:
                targets[equation.getColumn()] = 0;
                break;

            case SHUNT_TARGET_B:
                targets[equation.getColumn()] = network.getShunt(equation.getElementNum()).getB();
                break;

            case BRANCH_TARGET_P:
                targets[equation.getColumn()] = LfBranch.getDiscretePhaseControlTarget(network.getBranch(equation.getElementNum()), TransformerPhaseControl.Unit.MW);
                break;

            case BRANCH_TARGET_Q:
                targets[equation.getColumn()] = getReactivePowerControlTarget(network.getBranch(equation.getElementNum()));
                break;

            case BRANCH_TARGET_ALPHA1:
                targets[equation.getColumn()] = network.getBranch(equation.getElementNum()).getPiModel().getA1();
                break;

            case BRANCH_TARGET_RHO1:
                targets[equation.getColumn()] = network.getBranch(equation.getElementNum()).getPiModel().getR1();
                break;

            case DISTR_Q:
                targets[equation.getColumn()] = getGeneratorReactivePowerDistributionTarget(network, equation.getElementNum());
                break;

            case ZERO_V:
                targets[equation.getColumn()] = 0;
                break;

            case ZERO_PHI:
                targets[equation.getColumn()] = LfBranch.getA(network.getBranch(equation.getElementNum()));
                break;

            case DISTR_RHO,
                 DISTR_SHUNT_B,
                 DUMMY_TARGET_P,
                 DUMMY_TARGET_Q,
                 BUS_TARGET_IX_ZERO,
                 BUS_TARGET_IY_ZERO,
                 BUS_TARGET_IX_NEGATIVE,
                 BUS_TARGET_IY_NEGATIVE:
                targets[equation.getColumn()] = 0;
                break;

            default:
                throw new IllegalStateException("Unknown state variable type: " + equation.getType());
        }

        targets[equation.getColumn()] -= equation.rhs();
    }

    public AcTargetVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        super(network, equationSystem, AcTargetVector::init);
    }
}
