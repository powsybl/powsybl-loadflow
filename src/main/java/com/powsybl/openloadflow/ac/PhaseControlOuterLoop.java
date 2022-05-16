/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.reporter.Reporter;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide1CurrentMagnitudeEquationTerm;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide2CurrentMagnitudeEquationTerm;
import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class PhaseControlOuterLoop implements OuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhaseControlOuterLoop.class);

    @Override
    public String getType() {
        return "Phase control";
    }

    private static List<LfBranch> getControllerBranches(LfNetwork network) {
        return network.getBranches().stream()
                .filter(branch -> !branch.isDisabled() && branch.isPhaseController())
                .collect(Collectors.toList());
    }

    @Override
    public void initialize(OuterLoopContext context) {
        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        for (LfBranch controllerBranch : controllerBranches.stream()
                .filter(controllerBranch -> controllerBranch.getDiscretePhaseControl().orElseThrow().getMode() == DiscretePhaseControl.Mode.CONTROLLER)
                .collect(Collectors.toList())) {
            controllerBranch.setPhaseControlEnabled(true);
        }
        if (!controllerBranches.isEmpty()) {
            List<LfBranch> disabledBranches = context.getNetwork().getBranches().stream()
                    .filter(LfElement::isDisabled)
                    .collect(Collectors.toList());
            for (LfBranch controllerBranch : controllerBranches) {
                var phaseControl = controllerBranch.getDiscretePhaseControl().orElseThrow();
                var controlledBranch = phaseControl.getControlled();
                var connectivity = context.getNetwork().getConnectivity();

                // apply contingency (in case we are inside a security analysis)
                disabledBranches.stream()
                        .filter(b -> b.getBus1() != null && b.getBus2() != null)
                        .forEach(connectivity::cut);
                int smallComponentsCountBeforePhaseShifterLoss = connectivity.getSmallComponents().size();

                // then the phase shifter controlled branch
                if (controlledBranch.getBus1() != null && controlledBranch.getBus2() != null) {
                    connectivity.cut(controlledBranch);
                }

                if (connectivity.getSmallComponents().size() != smallComponentsCountBeforePhaseShifterLoss) {
                    // phase shifter controlled branch necessary for connectivity, we switch off control
                    LOGGER.warn("Phase shifter '{}' control branch '{}' phase but is necessary for connectivity: switch off phase control",
                            controllerBranch.getId(), controlledBranch.getId());
                    controllerBranch.setPhaseControlEnabled(false);
                }

                connectivity.reset();
            }
        }
    }

    @Override
    public OuterLoopStatus check(OuterLoopContext context, Reporter reporter) {
        if (context.getIteration() == 0) {
            // at first outer loop iteration:
            // branches with active power control are switched off and taps are rounded
            // branches with current limiter control will wait for second iteration
            return firstIteration(context);
        } else if (context.getIteration() > 0) {
            // at second outer loop iteration:
            // flow of branches with fixed tap are recomputed
            return nextIteration(context);
        }
        return OuterLoopStatus.STABLE;
    }

    private OuterLoopStatus firstIteration(OuterLoopContext context) {
        // all branches with active power control are switched off
        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        controllerBranches.stream()
                .flatMap(controllerBranch -> controllerBranch.getDiscretePhaseControl().stream())
                .filter(phaseControl -> phaseControl.getMode() == DiscretePhaseControl.Mode.CONTROLLER)
                .forEach(this::switchOffPhaseControl);

        // if at least one phase shifter has been switched off we need to continue
        return controllerBranches.isEmpty() ? OuterLoopStatus.STABLE : OuterLoopStatus.UNSTABLE;
    }

    private OuterLoopStatus nextIteration(OuterLoopContext context) {
        // at second outer loop iteration we switch on phase control for branches that are in limiter mode
        // and a current greater than the limit
        // phase control consists in increasing or decreasing tap position to limit the current
        List<DiscretePhaseControl> unstablePhaseControls = getControllerBranches(context.getNetwork()).stream()
                .flatMap(branch -> branch.getDiscretePhaseControl().stream())
                .filter(phaseControl -> phaseControl.getMode() == DiscretePhaseControl.Mode.LIMITER)
                .filter(this::changeTapPositions)
                .collect(Collectors.toList());

        return unstablePhaseControls.isEmpty() ? OuterLoopStatus.STABLE : OuterLoopStatus.UNSTABLE;
    }

    private void switchOffPhaseControl(DiscretePhaseControl phaseControl) {
        // switch off phase control
        LfBranch controllerBranch = phaseControl.getController();
        controllerBranch.setPhaseControlEnabled(false);

        // round the phase shift to the closest tap
        PiModel piModel = controllerBranch.getPiModel();
        double a1Value = piModel.getA1();
        piModel.roundA1ToClosestTap();
        double roundedA1Value = piModel.getA1();
        LOGGER.info("Round phase shift of '{}': {} -> {}", controllerBranch.getId(), a1Value, roundedA1Value);
    }

    private boolean changeTapPositions(DiscretePhaseControl phaseControl) {
        // only local control supported: controlled branch is controller branch.
        double currentLimit = phaseControl.getTargetValue();
        LfBranch controllerBranch = phaseControl.getController();
        PiModel piModel = controllerBranch.getPiModel();
        if (phaseControl.getControlledSide() == DiscretePhaseControl.ControlledSide.ONE && currentLimit < controllerBranch.getI1().eval()) {
            boolean isSensibilityPositive = isSensitivityCurrentPerA1Positive(controllerBranch, DiscretePhaseControl.ControlledSide.ONE);
            return isSensibilityPositive ? piModel.updateTapPosition(PiModel.Direction.DECREASE) : piModel.updateTapPosition(PiModel.Direction.INCREASE);
        } else if (phaseControl.getControlledSide() == DiscretePhaseControl.ControlledSide.TWO && currentLimit < controllerBranch.getI2().eval()) {
            boolean isSensibilityPositive = isSensitivityCurrentPerA1Positive(controllerBranch, DiscretePhaseControl.ControlledSide.TWO);
            return isSensibilityPositive ? piModel.updateTapPosition(PiModel.Direction.DECREASE) : piModel.updateTapPosition(PiModel.Direction.INCREASE);
        }
        return false;
    }

    private boolean isSensitivityCurrentPerA1Positive(LfBranch controllerBranch, DiscretePhaseControl.ControlledSide controlledSide) {
        if (controlledSide == DiscretePhaseControl.ControlledSide.ONE) {
            ClosedBranchSide1CurrentMagnitudeEquationTerm i1 = (ClosedBranchSide1CurrentMagnitudeEquationTerm) controllerBranch.getI1();
            Variable<AcVariableType> a1Var = i1.getVariables().stream().filter(v -> v.getType() == AcVariableType.BRANCH_ALPHA1).findFirst().orElseThrow();
            return i1.der(a1Var) > 0;
        } else {
            ClosedBranchSide2CurrentMagnitudeEquationTerm i2 = (ClosedBranchSide2CurrentMagnitudeEquationTerm) controllerBranch.getI2();
            Variable<AcVariableType> a1Var = i2.getVariables().stream().filter(v -> v.getType() == AcVariableType.BRANCH_ALPHA1).findFirst().orElseThrow();
            return i2.der(a1Var) > 0;
        }
    }
}
