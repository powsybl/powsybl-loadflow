/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.lf.AbstractEquationSystemUpdater;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.EvaluableConstants;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcEquationSystemUpdater extends AbstractEquationSystemUpdater<AcVariableType, AcEquationType> {

    private final AcEquationSystemCreator creator;

    private final AcEquationSystemCreationContext creationContext;

    public AcEquationSystemUpdater(AcEquationSystemCreator creator, AcEquationSystemCreationContext creationContext) {
        super(creationContext.getEquationSystem(), LoadFlowModel.AC);
        this.creator = Objects.requireNonNull(creator);
        this.creationContext = Objects.requireNonNull(creationContext);
    }

    private void updateVoltageControls(LfBus bus) {
        LfZeroImpedanceNetwork zn = bus.getZeroImpedanceNetwork(loadFlowModel);
        if (zn == null) {
            bus.getGeneratorVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateGeneratorVoltageControl(voltageControl.getMainVoltageControl(), creationContext));
            bus.getTransformerVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateTransformerVoltageControlEquations(voltageControl.getMainVoltageControl(), creationContext));
            bus.getShuntVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateShuntVoltageControlEquations(voltageControl.getMainVoltageControl(), creationContext));
        } else {
            for (LfBus zb : zn.getGraph().vertexSet()) {
                zb.getGeneratorVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateGeneratorVoltageControl(voltageControl.getMainVoltageControl(), creationContext));
                zb.getTransformerVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateTransformerVoltageControlEquations(voltageControl.getMainVoltageControl(), creationContext));
                zb.getShuntVoltageControl().ifPresent(voltageControl -> AcEquationSystemCreator.updateShuntVoltageControlEquations(voltageControl.getMainVoltageControl(), creationContext));
            }
        }
    }

    @Override
    public void onGeneratorVoltageControlChange(LfBus controllerBus, boolean newVoltageControllerEnabled) {
        updateVoltageControls(controllerBus.getGeneratorVoltageControl().orElseThrow().getControlledBus());
    }

    @Override
    public void onTransformerPhaseControlChange(LfBranch controllerBranch, boolean newPhaseControlEnabled) {
        AcEquationSystemCreator.updateTransformerPhaseControlEquations(controllerBranch.getPhaseControl().orElseThrow(), creationContext);
    }

    @Override
    public void onGeneratorReactivePowerControlChange(LfBus controllerBus, boolean newReactiveControllerEnabled) {
        controllerBus.getGeneratorReactivePowerControl().ifPresent(generatorReactivePowerControl -> AcEquationSystemCreator.updateGeneratorReactivePowerControlBranchEquations(generatorReactivePowerControl, creationContext));
    }

    @Override
    public void onTransformerVoltageControlChange(LfBranch controllerBranch, boolean newVoltageControllerEnabled) {
        updateVoltageControls(controllerBranch.getVoltageControl().orElseThrow().getControlledBus());
    }

    @Override
    public void onShuntVoltageControlChange(LfShunt controllerShunt, boolean newVoltageControllerEnabled) {
        updateVoltageControls(controllerShunt.getVoltageControl().orElseThrow().getControlledBus());
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
                        .ifPresent(eq -> eq.setActive(!bus.isDisabled() && bus.isReference()));
                equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_P)
                        .ifPresent(eq -> eq.setActive(!bus.isDisabled() && !bus.isSlack()));
                // set voltage target equation inactive, various voltage control will set next to the correct value
                equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_V)
                        .orElseThrow()
                        .setActive(false);
                bus.getGeneratorVoltageControl().ifPresent(vc -> updateVoltageControls(vc.getControlledBus()));
                bus.getTransformerVoltageControl().ifPresent(vc -> updateVoltageControls(vc.getControlledBus()));
                bus.getShuntVoltageControl().ifPresent(vc -> updateVoltageControls(vc.getControlledBus()));
                bus.getGeneratorReactivePowerControl().ifPresent(reactivePowerControl -> AcEquationSystemCreator.updateGeneratorReactivePowerControlBranchEquations(reactivePowerControl, creationContext));
                break;
            case BRANCH:
                LfBranch branch = (LfBranch) element;
                AcEquationSystemCreator.updateBranchEquations(branch);
                branch.getVoltageControl().ifPresent(vc -> updateVoltageControls(vc.getControlledBus()));
                branch.getPhaseControl().ifPresent(phaseControl -> AcEquationSystemCreator.updateTransformerPhaseControlEquations(phaseControl, creationContext));
                branch.getGeneratorReactivePowerControl().ifPresent(reactivePowerControl -> AcEquationSystemCreator.updateGeneratorReactivePowerControlBranchEquations(reactivePowerControl, creationContext));
                break;
            case SHUNT_COMPENSATOR:
                LfShunt shunt = (LfShunt) element;
                shunt.getVoltageControl().ifPresent(vc -> updateVoltageControls(vc.getControlledBus()));
                break;
            case HVDC:
                // nothing to do
                break;
            default:
                throw new IllegalStateException("Unknown element type: " + element.getType());
        }
    }

    private void recreateDistributionEquations(LfZeroImpedanceNetwork network) {
        for (LfBus bus : network.getGraph().vertexSet()) {
            bus.getGeneratorVoltageControl()
                    .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                    .ifPresent(voltageControl -> creator.recreateReactivePowerDistributionEquations(voltageControl, creationContext));
            bus.getTransformerVoltageControl()
                    .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                    .ifPresent(voltageControl -> AcEquationSystemCreator.recreateR1DistributionEquations(network.getNetwork(), voltageControl, creationContext));
            bus.getShuntVoltageControl()
                    .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                    .ifPresent(voltageControl -> AcEquationSystemCreator.recreateShuntSusceptanceDistributionEquations(network.getNetwork(), voltageControl, creationContext));
        }
    }

    @Override
    public void onZeroImpedanceNetworkSplit(LfZeroImpedanceNetwork initialNetwork, List<LfZeroImpedanceNetwork> splitNetworks, LoadFlowModel loadFlowModel) {
        if (loadFlowModel == LoadFlowModel.AC) {
            // TODO
            // only recreate distribution equations if controllers buses are redistributed on the different
            // split networks (should be a rare case) and not only ate the end on only one of the split network
            for (LfZeroImpedanceNetwork splitNetwork : splitNetworks) {
                recreateDistributionEquations(splitNetwork);
            }
        }
    }

    @Override
    public void onZeroImpedanceNetworkMerge(LfZeroImpedanceNetwork network1, LfZeroImpedanceNetwork network2, LfZeroImpedanceNetwork mergedNetwork, LoadFlowModel loadFlowModel) {
        if (loadFlowModel == LoadFlowModel.AC) {
            // TODO
            // only recreate distribution equations if controllers buses are merged (should be a rare case)
            // so we have to check here that controllers were spread over network1 and network2 and were not
            // already only on network1 or network2
            recreateDistributionEquations(mergedNetwork);
        }
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        AcEquationSystemCreator.updateBranchEquations(branch);
        if (branch.isConnectedSide1() && branch.isConnectedSide2()) {
            branch.setP1(branch.getClosedP1());
            branch.setQ1(branch.getClosedQ1());
            branch.setI1(branch.getClosedI1());
            branch.setP2(branch.getClosedP2());
            branch.setQ2(branch.getClosedQ2());
            branch.setI2(branch.getClosedI2());
        } else if (!branch.isConnectedSide1() && branch.isConnectedSide2()) {
            branch.setP1(EvaluableConstants.ZERO);
            branch.setQ1(EvaluableConstants.ZERO);
            branch.setI1(EvaluableConstants.ZERO);
            branch.setP2(branch.getOpenP2());
            branch.setQ2(branch.getOpenQ2());
            branch.setI2(branch.getOpenI2());
        } else if (branch.isConnectedSide1() && !branch.isConnectedSide2()) {
            branch.setP1(branch.getOpenP1());
            branch.setQ1(branch.getOpenQ1());
            branch.setI1(branch.getOpenI1());
            branch.setP2(EvaluableConstants.ZERO);
            branch.setQ2(EvaluableConstants.ZERO);
            branch.setI2(EvaluableConstants.ZERO);
        } else {
            branch.setP1(EvaluableConstants.NAN);
            branch.setQ1(EvaluableConstants.NAN);
            branch.setI1(EvaluableConstants.NAN);
            branch.setP2(EvaluableConstants.NAN);
            branch.setQ2(EvaluableConstants.NAN);
            branch.setI2(EvaluableConstants.NAN);
        }
    }

    @Override
    protected AcEquationType getTypeBusTargetP() {
        return AcEquationType.BUS_TARGET_P;
    }

    @Override
    protected AcEquationType getTypeBusTargetPhi() {
        return AcEquationType.BUS_TARGET_PHI;
    }

    @Override
    protected AcVariableType getTypeBusPhi() {
        return AcVariableType.BUS_PHI;
    }
}
