/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.TransformerPhaseControl.Mode;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.EvaluableConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.equations.EquationTerm.setActive;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcEquationSystemCreator {

    protected final LfNetwork network;

    protected final AcEquationSystemCreationParameters creationParameters;

    public AcEquationSystemCreator(LfNetwork network) {
        this(network, new AcEquationSystemCreationParameters());
    }

    public AcEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.creationParameters = Objects.requireNonNull(creationParameters);
    }

    protected void createBusEquation(LfBus bus, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        var p = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P);
        bus.setP(p);
        var q = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q);
        bus.setQ(q);

        if (bus.isReference()) {
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_PHI)
                    .addTerm(equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_PHI)
                            .createTerm());
        }
        if (bus.isSlack()) {
            p.setActive(false);
        }

        // maybe to fix later, but there is so part of OLF (like sensitivity) that needs a voltage target equation
        // deactivated
        EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V).createTerm();
        equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V)
                .addTerm(vTerm)
                .setActive(false);
        bus.setCalculatedV(vTerm);

        createShuntEquations(bus, creationContext);
        createLoadEquations(bus, creationContext);
    }

    private void createLoadEquations(LfBus bus, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        for (LfLoad load : bus.getLoads()) {
            load.getLoadModel().ifPresent(loadModel -> {
                var p = new LoadModelActiveFlowEquationTerm(bus, loadModel, load, equationSystem.getVariableSet());
                equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P)
                        .addTerm(p);
                load.setP(p);
                var q = new LoadModelReactiveFlowEquationTerm(bus, loadModel, load, equationSystem.getVariableSet());
                equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q)
                        .addTerm(q);
                load.setQ(q);
            });
        }
    }

    private void createVoltageControlEquations(AcEquationSystemCreationContext creationContext) {
        for (LfBus bus : network.getBuses()) {
            createGeneratorVoltageControlEquations(bus, creationContext);
            createTransformerVoltageControlEquations(bus, creationContext);
            createShuntVoltageControlEquations(bus, creationContext);
        }
    }

    private void createBusesEquations(AcEquationSystemCreationContext creationContext) {
        for (LfBus bus : network.getBuses()) {
            createBusEquation(bus, creationContext);
        }
    }

    private void createGeneratorVoltageControlEquations(LfBus bus, AcEquationSystemCreationContext creationContext) {
        bus.getGeneratorVoltageControl()
                .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .ifPresent(voltageControl -> {
                    if (bus.isGeneratorVoltageControlled()) {
                        if (voltageControl.isLocalControl()) {
                            createGeneratorLocalVoltageControlEquation(bus, creationContext);
                        } else {
                            // create reactive power distribution equations at voltage controller buses
                            createGeneratorReactivePowerDistributionEquations(voltageControl, creationContext, creationParameters);
                        }
                        updateGeneratorVoltageControl(voltageControl, creationContext);
                    }
                });
    }

    private void createGeneratorLocalVoltageControlEquation(LfBus bus, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        if (bus.hasGeneratorsWithSlope()) {
            // take first generator with slope: network loading ensures that there's only one generator with slope
            double slope = bus.getGeneratorsControllingVoltageWithSlope().get(0).getSlope();

            // we only support one generator controlling voltage with a non zero slope at a bus.
            // equation is: V + slope * qSVC = targetV
            // which is modeled here with: V + slope * (sum_branch qBranch) = TargetV - slope * qLoads + slope * qGenerators
            equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_V).orElseThrow()
                    .addTerms(createReactiveTerms(bus, creationContext, creationParameters)
                            .stream()
                            .map(term -> term.multiply(slope))
                            .collect(Collectors.toList()));
            // to update open/close terms activation
            for (LfBranch branch : bus.getBranches()) {
                updateBranchEquations(branch);
            }
        }
    }

    protected void createGeneratorReactivePowerControlBranchEquation(LfBranch branch, LfBus bus1, LfBus bus2, AcEquationSystemCreationContext creationContext,
                                                                     boolean deriveA1, boolean deriveR1) {
        if (bus1 != null && bus2 != null) {
            var equationSystem = creationContext.getEquationSystem();
            branch.getGeneratorReactivePowerControl().ifPresent(rpc -> {
                BaseEquationTerm<AcVariableType, AcEquationType> q = rpc.getControlledSide() == TwoSides.ONE
                        ? createClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext)
                        : createClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
                equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_Q)
                        .addTerm(q);
                createGeneratorReactivePowerDistributionEquations(rpc, creationContext, creationParameters);
                updateGeneratorReactivePowerControlBranchEquations(rpc, creationContext);
            });
        }
    }

    public static void updateGeneratorReactivePowerControlBranchEquations(GeneratorReactivePowerControl generatorReactivePowerControl,
                                                                          AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        LfBranch controlledBranch = generatorReactivePowerControl.getControlledBranch();
        List<LfBus> controllerBuses = generatorReactivePowerControl.getControllerBuses()
                .stream()
                .filter(b -> !b.isDisabled()) // discard disabled controller elements
                .toList();
        BaseEquation<AcVariableType, AcEquationType> qEq = equationSystem.getEquation(controlledBranch.getNum(), AcEquationType.BRANCH_TARGET_Q)
                .orElseThrow();

        if (controlledBranch.isDisabled()) {
            qEq.setActive(false);
            for (LfBus controllerBus : controllerBuses) {
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.DISTR_Q)
                        .ifPresent(eq -> eq.setActive(false));
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(true);
            }
        } else {
            List<LfBus> enabledControllerBuses = controllerBuses.stream()
                    .filter(LfBus::isGeneratorReactivePowerControlEnabled).toList();
            List<LfBus> disabledControllerBuses = controllerBuses.stream()
                    .filter(Predicate.not(LfBus::isGeneratorReactivePowerControlEnabled)).toList();

            // reactive keys must be updated in case of disabled controllers.
            generatorReactivePowerControl.updateReactiveKeys();

            // activate reactive power control at controlled bus only if at least one controller element is enabled
            qEq.setActive(!enabledControllerBuses.isEmpty());

            for (LfBus controllerElement : disabledControllerBuses) {
                equationSystem.getEquation(controllerElement.getNum(), AcEquationType.DISTR_Q)
                        .ifPresent(eq -> eq.setActive(false));
                equationSystem.getEquation(controllerElement.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(true);
            }

            // activate distribution equation and deactivate control equation at all enabled controller buses except one (first)
            for (int i = 0; i < enabledControllerBuses.size(); i++) {
                boolean active = i != 0;
                LfBus controllerElement = enabledControllerBuses.get(i);
                equationSystem.getEquation(controllerElement.getNum(), AcEquationType.DISTR_Q)
                        .ifPresent(eq -> eq.setActive(active));
                equationSystem.getEquation(controllerElement.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(false);
            }
        }
    }

    private void createShuntEquation(LfShunt shunt, LfBus bus, boolean deriveB, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        var q = createShuntCompensatorReactiveFlowEquationTerm(shunt, bus, deriveB, creationContext);
        equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_Q).orElseThrow()
                .addTerm(q);
        shunt.setQ(q);
        var p = createShuntCompensatorActiveFlowEquationTerm(shunt, bus, creationContext);
        equationSystem.getEquation(bus.getNum(), AcEquationType.BUS_TARGET_P).orElseThrow()
                .addTerm(p);
        shunt.setP(p);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createShuntCompensatorActiveFlowEquationTerm(LfShunt shunt, LfBus bus, AcEquationSystemCreationContext creationContext) {
        return new ShuntCompensatorActiveFlowEquationTerm(shunt, bus, creationContext.getEquationSystem().getVariableSet());
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createShuntCompensatorReactiveFlowEquationTerm(LfShunt shunt, LfBus bus, boolean deriveB, AcEquationSystemCreationContext creationContext) {
        return new ShuntCompensatorReactiveFlowEquationTerm(shunt, bus, creationContext.getEquationSystem().getVariableSet(), deriveB);
    }

    private void createShuntEquations(LfBus bus, AcEquationSystemCreationContext creationContext) {
        bus.getShunt().ifPresent(shunt -> createShuntEquation(shunt, bus, false, creationContext));
        bus.getControllerShunt().ifPresent(shunt -> createShuntEquation(shunt, bus, shunt.hasVoltageControlCapability(), creationContext));
        bus.getSvcShunt().ifPresent(shunt -> createShuntEquation(shunt, bus, false, creationContext));
    }

    private void createGeneratorReactivePowerDistributionEquations(Control control, AcEquationSystemCreationContext creationContext,
                                                                   AcEquationSystemCreationParameters creationParameters) {
        List<LfBus> controllerBuses;
        if (control instanceof GeneratorVoltageControl generatorVoltageControl) {
            controllerBuses = generatorVoltageControl.getMergedControllerElements();
        } else if (control instanceof GeneratorReactivePowerControl generatorReactivePowerControl) {
            controllerBuses = generatorReactivePowerControl.getControllerBuses();
        } else {
            throw new PowsyblException("Control has to be of type GeneratorVoltageControl or ReactivePowerControl to create Q distribution equations");
        }

        var equationSystem = creationContext.getEquationSystem();
        for (LfBus controllerBus : controllerBuses) {
            // reactive power at controller bus i (supposing this voltage control is enabled)
            // q_i = qPercent_i * sum_j(q_j) where j are all the voltage controller buses
            // 0 = qPercent_i * sum_j(q_j) - q_i
            // which can be rewritten in a more simple way
            // 0 = (qPercent_i - 1) * q_i + qPercent_i * sum_j(q_j) where j are all the voltage controller buses except i
            BaseEquation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerBus, AcEquationType.DISTR_Q)
                    .addTerms(createReactiveTerms(controllerBus, creationContext, creationParameters).stream()
                            .map(term -> term.multiply(() -> controllerBus.getRemoteControlReactivePercent() - 1))
                            .collect(Collectors.toList()));
            // to update open/close terms activation
            for (LfBranch branch : controllerBus.getBranches()) {
                updateBranchEquations(branch);
            }
            for (LfBus otherControllerBus : controllerBuses) {
                if (otherControllerBus != controllerBus) {
                    zero.addTerms(createReactiveTerms(otherControllerBus, creationContext, creationParameters).stream()
                            .map(term -> term.multiply(controllerBus::getRemoteControlReactivePercent))
                            .collect(Collectors.toList()));
                }
                // to update open/close terms activation
                for (LfBranch branch : otherControllerBus.getBranches()) {
                    updateBranchEquations(branch);
                }
            }
        }
    }

    private static void removeEquationAndCleanElement(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, int elementNum, AcEquationType equationType) {
        var removedEq = equationSystem.removeEquation(elementNum, equationType);
        if (removedEq != null) {
            for (var term : removedEq.getLeafTerms()) {
                LfElement element = network.getElement(term.getElementType(), term.getElementNum());
                element.removeEvaluable(term);
            }
        }
    }

    public void recreateReactivePowerDistributionEquations(GeneratorVoltageControl voltageControl,
                                                           AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        for (LfBus controllerBus : voltageControl.getMergedControllerElements()) {
            removeEquationAndCleanElement(network, equationSystem, controllerBus.getNum(), AcEquationType.DISTR_Q);
        }
        if (!voltageControl.isLocalControl()) {
            createGeneratorReactivePowerDistributionEquations(voltageControl, creationContext, creationParameters);
        }
        updateGeneratorVoltageControl(voltageControl, creationContext);
    }

    static <T extends LfElement> void updateRemoteVoltageControlEquations(VoltageControl<T> voltageControl,
                                                                          AcEquationSystemCreationContext creationContext,
                                                                          AcEquationType distrEqType, AcEquationType ctrlEqType) {
        checkNotDependentVoltageControl(voltageControl);

        var equationSystem = creationContext.getEquationSystem();

        LfBus controlledBus = voltageControl.getControlledBus();

        List<T> controllerElements = voltageControl.getMergedControllerElements()
                .stream()
                .filter(b -> !b.isDisabled()) // discard disabled controller elements
                .toList();

        BaseEquation<AcVariableType, AcEquationType> vEq = equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V)
                .orElseThrow();

        List<BaseEquation<AcVariableType, AcEquationType>> vEqMergedList = voltageControl.getMergedDependentVoltageControls().stream()
                .map(vc -> equationSystem.getEquation(vc.getControlledBus().getNum(), AcEquationType.BUS_TARGET_V).orElseThrow())
                .toList();

        if (voltageControl.isHidden()) {
            voltageControl.findMainVisibleControlledBus().ifPresentOrElse(mainVisibleControlledBus -> {
                if (mainVisibleControlledBus != voltageControl.getControlledBus()) {
                    vEq.setActive(false);
                }
            }, () -> vEq.setActive(false));
            for (T controllerElement : controllerElements) {
                equationSystem.getEquation(controllerElement.getNum(), distrEqType)
                        .ifPresent(eq -> eq.setActive(false));
                equationSystem.getEquation(controllerElement.getNum(), ctrlEqType)
                        .orElseThrow()
                        .setActive(true);
            }
        } else {
            List<T> enabledControllerElements = controllerElements.stream()
                    .filter(voltageControl::isControllerEnabled).toList();
            List<T> disabledControllerElements = controllerElements.stream()
                    .filter(Predicate.not(voltageControl::isControllerEnabled)).toList();

            // activate voltage control at controlled bus only if at least one controller element is enabled
            vEq.setActive(!enabledControllerElements.isEmpty());

            // deactivate voltage control for merged controlled buses
            for (var vEqMerged : vEqMergedList) {
                vEqMerged.setActive(false);
            }

            // deactivate distribution equations and reactivate control equations
            for (T controllerElement : disabledControllerElements) {
                equationSystem.getEquation(controllerElement.getNum(), distrEqType)
                        .ifPresent(eq -> eq.setActive(false));
                equationSystem.getEquation(controllerElement.getNum(), ctrlEqType)
                        .orElseThrow()
                        .setActive(true);
            }

            // activate distribution equation and deactivate control equation at all enabled controller buses except one (first)
            for (int i = 0; i < enabledControllerElements.size(); i++) {
                boolean active = i != 0;
                T controllerElement = enabledControllerElements.get(i);
                equationSystem.getEquation(controllerElement.getNum(), distrEqType)
                        .ifPresent(eq -> eq.setActive(active));
                equationSystem.getEquation(controllerElement.getNum(), ctrlEqType)
                        .orElseThrow()
                        .setActive(false);
            }
        }
    }

    private List<BaseEquationTerm<AcVariableType, AcEquationType>> createReactiveTerms(LfBus controllerBus,
                                                                                       AcEquationSystemCreationContext creationContext,
                                                                                       AcEquationSystemCreationParameters creationParameters) {
        var variableSet = creationContext.getEquationSystem().getVariableSet();
        List<BaseEquationTerm<AcVariableType, AcEquationType>> terms = new ArrayList<>();
        for (LfBranch branch : controllerBus.getBranches()) {
            EquationTerm<AcVariableType, AcEquationType> q;
            EquationTerm<AcVariableType, AcEquationType> openQ = null;
            if (branch.isZeroImpedance(LoadFlowModel.AC)) {
                if (!branch.isSpanningTreeEdge(LoadFlowModel.AC)) {
                    continue;
                }
                if (branch.getBus1() == controllerBus) {
                    q = variableSet.getVariable(branch.getNum(), AcVariableType.DUMMY_Q).createTerm();
                } else {
                    q = variableSet.getVariable(branch.getNum(), AcVariableType.DUMMY_Q).<AcEquationType>createTerm()
                                    .minus();
                }
            } else {
                boolean deriveA1 = isDeriveA1(branch, creationParameters);
                boolean deriveR1 = isDeriveR1(branch);
                if (branch.getBus1() == controllerBus) {
                    LfBus otherSideBus = branch.getBus2();
                    if (otherSideBus != null) {
                        q = new ClosedBranchSide1ReactiveFlowEquationTerm(branch, controllerBus, otherSideBus, variableSet, deriveA1, deriveR1);
                        branch.addAdditionalClosedQ1(q);
                        if (branch.isDisconnectionAllowedSide2()) {
                            openQ = new OpenBranchSide2ReactiveFlowEquationTerm(branch, controllerBus, variableSet);
                            branch.addAdditionalOpenQ1(openQ);
                        }
                    } else {
                        q = new OpenBranchSide2ReactiveFlowEquationTerm(branch, controllerBus, variableSet);
                        branch.addAdditionalOpenQ1(q);
                    }
                } else {
                    LfBus otherSideBus = branch.getBus1();
                    if (otherSideBus != null) {
                        q = new ClosedBranchSide2ReactiveFlowEquationTerm(branch, otherSideBus, controllerBus, variableSet, deriveA1, deriveR1);
                        branch.addAdditionalClosedQ2(q);
                        if (branch.isDisconnectionAllowedSide1()) {
                            openQ = new OpenBranchSide1ReactiveFlowEquationTerm(branch, controllerBus, variableSet);
                            branch.addAdditionalOpenQ2(openQ);
                        }
                    } else {
                        q = new OpenBranchSide1ReactiveFlowEquationTerm(branch, controllerBus, variableSet);
                        branch.addAdditionalOpenQ2(q);
                    }
                }
            }
            terms.add(q);
            if (openQ != null) {
                terms.add(openQ);
            }
        }
        controllerBus.getShunt().ifPresent(shunt -> {
            var q = createShuntCompensatorReactiveFlowEquationTerm(shunt, controllerBus, false, creationContext);
            terms.add(q);
        });
        controllerBus.getControllerShunt().ifPresent(shunt -> {
            var q = createShuntCompensatorReactiveFlowEquationTerm(shunt, controllerBus, false, creationContext);
            terms.add(q);
        });
        return terms;
    }

    public static void updateGeneratorVoltageControl(GeneratorVoltageControl voltageControl, AcEquationSystemCreationContext creationContext) {
        checkNotDependentVoltageControl(voltageControl);

        // ensure reactive keys are up-to-date
        voltageControl.updateReactiveKeys();

        updateRemoteVoltageControlEquations(voltageControl, creationContext, AcEquationType.DISTR_Q, AcEquationType.BUS_TARGET_Q);
    }

    private static <T extends LfElement> void checkNotDependentVoltageControl(VoltageControl<T> voltageControl) {
        if (voltageControl.getMergeStatus() == VoltageControl.MergeStatus.DEPENDENT) {
            throw new IllegalArgumentException("Cannot update a merged dependent voltage control");
        }
    }

    private static void createNonImpedantBranch(LfBranch branch, LfBus bus1, LfBus bus2,
                                                AcEquationSystemCreationContext creationContext,
                                                boolean spanningTreeEdge) {
        var equationSystem = creationContext.getEquationSystem();
        if (bus1 != null && bus2 != null) {
            Optional<BaseEquation<AcVariableType, AcEquationType>> v1 = equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_V);
            Optional<BaseEquation<AcVariableType, AcEquationType>> v2 = equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_V);
            boolean hasV1 = v1.isPresent() && v1.get().isActive(); // may be inactive if the equation has been created for sensitivity
            boolean hasV2 = v2.isPresent() && v2.get().isActive(); // may be inactive if the equation has been created for sensitivity
            boolean enabled = !branch.isDisabled() && spanningTreeEdge;
            if (!(hasV1 && hasV2)) {
                // create voltage magnitude coupling equation
                // 0 = v1 - v2 * rho
                PiModel piModel = branch.getPiModel();
                double rho = PiModel.R2 / piModel.getR1();
                EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus1.getNum(), AcVariableType.BUS_V)
                        .createTerm();
                EquationTerm<AcVariableType, AcEquationType> bus2vTerm = equationSystem.getVariable(bus2.getNum(), AcVariableType.BUS_V)
                        .createTerm();
                equationSystem.createEquation(branch, AcEquationType.ZERO_V)
                        .addTerm(vTerm)
                        .addTerm(bus2vTerm.multiply(-rho))
                        .setActive(enabled);

                // add a dummy reactive power variable to both sides of the non impedant branch and with an opposite sign
                // to ensure we have the same number of equation and variables
                var dummyQ = equationSystem.getVariable(branch.getNum(), AcVariableType.DUMMY_Q);
                equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .addTerm(dummyQ.createTerm());

                equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .addTerm(dummyQ.<AcEquationType>createTerm()
                                .minus());

                // create an inactive dummy reactive power target equation set to zero that could be activated
                // on case of switch opening
                equationSystem.createEquation(branch, AcEquationType.DUMMY_TARGET_Q)
                        .addTerm(dummyQ.createTerm())
                        .setActive(!enabled); // inverted logic
            } else {
                // nothing to do in case of v1 and v2 are found, we just have to ensure
                // target v are equals.
            }

            boolean hasPhi1 = equationSystem.hasEquation(bus1.getNum(), AcEquationType.BUS_TARGET_PHI);
            boolean hasPhi2 = equationSystem.hasEquation(bus2.getNum(), AcEquationType.BUS_TARGET_PHI);
            if (!(hasPhi1 && hasPhi2)) {
                // create voltage angle coupling equation
                // alpha = phi1 - phi2
                equationSystem.createEquation(branch, AcEquationType.ZERO_PHI)
                        .addTerm(equationSystem.getVariable(bus1.getNum(), AcVariableType.BUS_PHI).createTerm())
                        .addTerm(equationSystem.getVariable(bus2.getNum(), AcVariableType.BUS_PHI).<AcEquationType>createTerm()
                                .minus())
                        .setActive(enabled);

                // add a dummy active power variable to both sides of the non impedant branch and with an opposite sign
                // to ensure we have the same number of equation and variables
                var dummyP = equationSystem.getVariable(branch.getNum(), AcVariableType.DUMMY_P);
                equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_P)
                        .orElseThrow()
                        .addTerm(dummyP.createTerm());

                equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_P)
                        .orElseThrow()
                        .addTerm(dummyP.<AcEquationType>createTerm()
                                .minus());

                // create an inactive dummy active power target equation set to zero that could be activated
                // on case of switch opening
                equationSystem.createEquation(branch, AcEquationType.DUMMY_TARGET_P)
                        .addTerm(dummyP.createTerm())
                        .setActive(!enabled); // inverted logic
            } else {
                throw new IllegalStateException("Cannot happen because only there is one slack bus per model");
            }
        }
    }

    protected void createTransformerPhaseControlEquations(LfBranch branch, LfBus bus1, LfBus bus2, AcEquationSystemCreationContext creationContext,
                                                          boolean deriveA1, boolean deriveR1) {
        var equationSystem = creationContext.getEquationSystem();
        if (deriveA1) {
            EquationTerm<AcVariableType, AcEquationType> a1 = equationSystem.getVariable(branch.getNum(), AcVariableType.BRANCH_ALPHA1)
                    .createTerm();
            branch.setA1(a1);
            equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_ALPHA1)
                    .addTerm(a1);
        }

        if (branch.isPhaseControlled()) {
            TransformerPhaseControl phaseControl = branch.getPhaseControl().orElseThrow();
            if (phaseControl.getMode() == Mode.CONTROLLER) {
                if (phaseControl.getUnit() == TransformerPhaseControl.Unit.A) {
                    throw new PowsyblException("Phase control in A is not yet supported");
                }

                BaseEquationTerm<AcVariableType, AcEquationType> p = phaseControl.getControlledSide() == TwoSides.ONE
                        ? createClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext)
                        : createClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
                equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_P)
                        .addTerm(p)
                        .setActive(false); // by default BRANCH_TARGET_ALPHA1 is active and BRANCH_TARGET_P inactive
            }
        }
    }

    protected static void createTransformerReactivePowerControlEquations(LfBranch branch, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        if (branch.isTransformerReactivePowerController()) {
            // constant R1 equation for sensitivities only
            EquationTerm<AcVariableType, AcEquationType> r1 = equationSystem.getVariable(branch.getNum(), AcVariableType.BRANCH_RHO1)
                    .createTerm();
            equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_RHO1)
                    .addTerm(r1);
        }
    }

    public static void updateTransformerPhaseControlEquations(TransformerPhaseControl phaseControl, AcEquationSystemCreationContext creationContext) {
        LfBranch controllerBranch = phaseControl.getControllerBranch();
        LfBranch controlledBranch = phaseControl.getControlledBranch();

        var equationSystem = creationContext.getEquationSystem();

        if (phaseControl.getMode() == Mode.CONTROLLER) {
            boolean controlEnabled = !controllerBranch.isDisabled() && !controlledBranch.isDisabled() && controllerBranch.isPhaseControlEnabled();

            // activate/de-activate phase control equation
            equationSystem.getEquation(controlledBranch.getNum(), AcEquationType.BRANCH_TARGET_P)
                    .orElseThrow()
                    .setActive(controlEnabled);

            // de-activate/activate constant A1 equation
            equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_ALPHA1)
                    .orElseThrow()
                    .setActive(!controlEnabled && !controllerBranch.isDisabled());
        } else {
            equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_ALPHA1)
                    .orElseThrow()
                    .setActive(!controllerBranch.isDisabled());
        }
    }

    private static void createTransformerVoltageControlEquations(LfBus bus, AcEquationSystemCreationContext creationContext) {
        bus.getTransformerVoltageControl()
                .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .ifPresent(voltageControl -> {
                    // add transformer ratio distribution equations
                    createR1DistributionEquations(voltageControl, creationContext);

                    var equationSystem = creationContext.getEquationSystem();

                    // we also create an equation per controller that will be used later to maintain R1 variable constant
                    for (LfBranch controllerBranch : voltageControl.getMergedControllerElements()) {
                        equationSystem.createEquation(controllerBranch, AcEquationType.BRANCH_TARGET_RHO1)
                                .addTerm(equationSystem.getVariable(controllerBranch.getNum(), AcVariableType.BRANCH_RHO1).createTerm());
                    }

                    updateTransformerVoltageControlEquations(voltageControl, creationContext);
                });
    }

    public static void createR1DistributionEquations(TransformerVoltageControl voltageControl,
                                                     AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        var controllerBranches = voltageControl.getMergedControllerElements();
        for (int i = 0; i < controllerBranches.size(); i++) {
            LfBranch controllerBranch = controllerBranches.get(i);
            // r1 at controller branch i
            // r1_i = sum_j(r1_j) / controller_count where j are all the controller branches
            // 0 = sum_j(r1_j) / controller_count - r1_i
            // which can be rewritten in a more simple way
            // 0 = (1 / controller_count - 1) * r1_i + sum_j(r1_j) / controller_count where j are all the controller branches except i
            EquationTerm<AcVariableType, AcEquationType> r1 = equationSystem.getVariable(controllerBranch.getNum(), AcVariableType.BRANCH_RHO1)
                    .createTerm();
            BaseEquation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerBranch, AcEquationType.DISTR_RHO)
                    .addTerm(r1.multiply(() -> 1d / controllerBranches.stream().filter(b -> !b.isDisabled()).count() - 1));
            for (LfBranch otherControllerBranch : controllerBranches) {
                if (otherControllerBranch != controllerBranch) {
                    EquationTerm<AcVariableType, AcEquationType> otherR1 = equationSystem.getVariable(otherControllerBranch.getNum(), AcVariableType.BRANCH_RHO1)
                            .createTerm();
                    zero.addTerm(otherR1.multiply(() -> 1d / controllerBranches.stream().filter(b -> !b.isDisabled()).count()));
                }
            }
        }
    }

    static void updateTransformerVoltageControlEquations(TransformerVoltageControl voltageControl, AcEquationSystemCreationContext creationContext) {
        updateRemoteVoltageControlEquations(voltageControl, creationContext, AcEquationType.DISTR_RHO, AcEquationType.BRANCH_TARGET_RHO1);
    }

    public static void recreateR1DistributionEquations(LfNetwork network,
                                                       TransformerVoltageControl voltageControl,
                                                       AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        for (LfBranch controllerBranch : voltageControl.getMergedControllerElements()) {
            removeEquationAndCleanElement(network, equationSystem, controllerBranch.getNum(), AcEquationType.DISTR_RHO);
        }
        createR1DistributionEquations(voltageControl, creationContext);
        updateTransformerVoltageControlEquations(voltageControl, creationContext);
    }

    private static void createShuntVoltageControlEquations(LfBus bus, AcEquationSystemCreationContext creationContext) {
        bus.getShuntVoltageControl()
                .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                .ifPresent(voltageControl -> {
                    // add shunt distribution equations
                    createShuntSusceptanceDistributionEquations(voltageControl, creationContext);

                    var equationSystem = creationContext.getEquationSystem();

                    for (LfShunt controllerShunt : voltageControl.getMergedControllerElements()) {
                        // we also create an equation that will be used later to maintain B variable constant
                        // this equation is now inactive
                        equationSystem.createEquation(controllerShunt, AcEquationType.SHUNT_TARGET_B)
                                .addTerm(equationSystem.getVariable(controllerShunt.getNum(), AcVariableType.SHUNT_B).createTerm());
                    }

                    updateShuntVoltageControlEquations(voltageControl, creationContext);
                });
    }

    public static void createShuntSusceptanceDistributionEquations(ShuntVoltageControl voltageControl,
                                                                   AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        var controllerShunts = voltageControl.getMergedControllerElements();
        for (LfShunt controllerShunt : controllerShunts) {
            // shunt b at controller bus i
            // b_i = sum_j(b_j) / controller_count where j are all the controller buses
            // 0 = sum_j(b_j) / controller_count - b_i
            // which can be rewritten in a more simple way
            // 0 = (1 / controller_count - 1) * b_i + sum_j(b_j) / controller_count where j are all the controller buses except i
            EquationTerm<AcVariableType, AcEquationType> shuntB = equationSystem.getVariable(controllerShunt.getNum(), AcVariableType.SHUNT_B)
                    .createTerm();
            BaseEquation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerShunt, AcEquationType.DISTR_SHUNT_B)
                    .addTerm(shuntB.multiply(() -> 1d / controllerShunts.stream().filter(b -> !b.isDisabled()).count() - 1));
            for (LfShunt otherControllerShunt : controllerShunts) {
                if (otherControllerShunt != controllerShunt) {
                    EquationTerm<AcVariableType, AcEquationType> otherShuntB = equationSystem.getVariable(otherControllerShunt.getNum(), AcVariableType.SHUNT_B)
                            .createTerm();
                    zero.addTerm(otherShuntB.multiply(() -> 1d / controllerShunts.stream().filter(b -> !b.isDisabled()).count()));
                }
            }
        }
    }

    static void updateShuntVoltageControlEquations(ShuntVoltageControl voltageControl, AcEquationSystemCreationContext creationContext) {
        updateRemoteVoltageControlEquations(voltageControl, creationContext, AcEquationType.DISTR_SHUNT_B, AcEquationType.SHUNT_TARGET_B);
    }

    public static void recreateShuntSusceptanceDistributionEquations(LfNetwork network,
                                                                     ShuntVoltageControl voltageControl,
                                                                     AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        for (LfShunt controllerShunt : voltageControl.getMergedControllerElements()) {
            removeEquationAndCleanElement(network, equationSystem, controllerShunt.getNum(), AcEquationType.DISTR_SHUNT_B);
        }
        createShuntSusceptanceDistributionEquations(voltageControl, creationContext);
        updateShuntVoltageControlEquations(voltageControl, creationContext);
    }

    public static boolean isDeriveA1(LfBranch branch, AcEquationSystemCreationParameters creationParameters) {
        return branch.isPhaseController()
                || creationParameters.isForceA1Var() && branch.hasPhaseControllerCapability() && branch.isConnectedAtBothSides();
    }

    public static boolean isDeriveR1(LfBranch branch) {
        return branch.isVoltageController() || branch.isTransformerReactivePowerController();
    }

    protected void createImpedantBranch(LfBranch branch, LfBus bus1, LfBus bus2,
                                        AcEquationSystemCreationContext creationContext) {
        // effective equations, could be closed one or open one
        Evaluable p1 = null;
        Evaluable q1 = null;
        Evaluable p2 = null;
        Evaluable q2 = null;
        Evaluable i1 = null;
        Evaluable i2 = null;

        // closed equations, could be null because line already open on base case
        BaseEquationTerm<AcVariableType, AcEquationType> closedP1 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> closedQ1 = null;
        EquationTerm<AcVariableType, AcEquationType> closedI1 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> closedP2 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> closedQ2 = null;
        EquationTerm<AcVariableType, AcEquationType> closedI2 = null;

        // open equations, could be null because only necessary if already open and never closed, or open during simulation
        BaseEquationTerm<AcVariableType, AcEquationType> openP1 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> openQ1 = null;
        EquationTerm<AcVariableType, AcEquationType> openI1 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> openP2 = null;
        BaseEquationTerm<AcVariableType, AcEquationType> openQ2 = null;
        EquationTerm<AcVariableType, AcEquationType> openI2 = null;

        boolean deriveA1 = isDeriveA1(branch, creationParameters);
        boolean deriveR1 = isDeriveR1(branch);
        if (bus1 != null && bus2 != null) {
            closedP1 = createClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            closedQ1 = createClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            closedP2 = createClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            closedQ2 = createClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            closedI1 = createClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            closedI2 = createClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, deriveA1, deriveR1, creationContext);
            if (branch.isDisconnectionAllowedSide1()) {
                openP2 = createOpenBranchSide1ActiveFlowEquationTerm(branch, bus2, creationContext);
                openQ2 = createOpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, creationContext);
                openI2 = createOpenBranchSide1CurrentMagnitudeEquationTerm(branch, bus2, creationContext);
            }
            if (branch.isDisconnectionAllowedSide2()) {
                openP1 = createOpenBranchSide2ActiveFlowEquationTerm(branch, bus1, creationContext);
                openQ1 = createOpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, creationContext);
                openI1 = createOpenBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, deriveR1, creationContext);
            }
            p1 = closedP1;
            q1 = closedQ1;
            i1 = closedI1;
            p2 = closedP2;
            q2 = closedQ2;
            i2 = closedI2;
        } else if (bus1 != null) {
            openP1 = createOpenBranchSide2ActiveFlowEquationTerm(branch, bus1, creationContext);
            openQ1 = createOpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, creationContext);
            openI1 = createOpenBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, deriveR1, creationContext);
            p1 = openP1;
            q1 = openQ1;
            i1 = openI1;
            p2 = EvaluableConstants.ZERO;
            q2 = EvaluableConstants.ZERO;
            i2 = EvaluableConstants.ZERO;
        } else if (bus2 != null) {
            openP2 = createOpenBranchSide1ActiveFlowEquationTerm(branch, bus2, creationContext);
            openQ2 = createOpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, creationContext);
            openI2 = createOpenBranchSide1CurrentMagnitudeEquationTerm(branch, bus2, creationContext);
            p1 = EvaluableConstants.ZERO;
            q1 = EvaluableConstants.ZERO;
            i1 = EvaluableConstants.ZERO;
            p2 = openP2;
            q2 = openQ2;
            i2 = openI2;
        }

        createImpedantBranchEquations(branch, bus1, bus2, creationContext,
                p1, q1, i1,
                p2, q2, i2,
                closedP1, closedQ1, closedI1,
                closedP2, closedQ2, closedI2,
                openP1, openQ1, openI1,
                openP2, openQ2, openI2);

        createGeneratorReactivePowerControlBranchEquation(branch, bus1, bus2, creationContext, deriveA1, deriveR1);

        createTransformerPhaseControlEquations(branch, bus1, bus2, creationContext, deriveA1, deriveR1);

        updateBranchEquations(branch);

        createTransformerReactivePowerControlEquations(branch, creationContext);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide2ActiveFlowEquationTerm(branch, bus1, creationContext.getEquationSystem().getVariableSet());
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, creationContext.getEquationSystem().getVariableSet());
    }

    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, creationContext.getEquationSystem().getVariableSet(), deriveR1);
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide1ActiveFlowEquationTerm(branch, bus2, creationContext.getEquationSystem().getVariableSet());
    }

    protected BaseEquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, creationContext.getEquationSystem().getVariableSet());
    }

    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchSide1CurrentMagnitudeEquationTerm(branch, bus2, creationContext.getEquationSystem().getVariableSet());
    }

    protected void createImpedantBranchEquations(LfBranch branch, LfBus bus1, LfBus bus2, AcEquationSystemCreationContext creationContext,
                                                 Evaluable p1, Evaluable q1, Evaluable i1,
                                                 Evaluable p2, Evaluable q2, Evaluable i2,
                                                 BaseEquationTerm<AcVariableType, AcEquationType> closedP1, BaseEquationTerm<AcVariableType, AcEquationType> closedQ1, EquationTerm<AcVariableType, AcEquationType> closedI1,
                                                 BaseEquationTerm<AcVariableType, AcEquationType> closedP2, BaseEquationTerm<AcVariableType, AcEquationType> closedQ2, EquationTerm<AcVariableType, AcEquationType> closedI2,
                                                 BaseEquationTerm<AcVariableType, AcEquationType> openP1, BaseEquationTerm<AcVariableType, AcEquationType> openQ1, EquationTerm<AcVariableType, AcEquationType> openI1,
                                                 BaseEquationTerm<AcVariableType, AcEquationType> openP2, BaseEquationTerm<AcVariableType, AcEquationType> openQ2, EquationTerm<AcVariableType, AcEquationType> openI2) {
        var equationSystem = creationContext.getEquationSystem();
        if (closedP1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_P).orElseThrow()
                    .addTerm(closedP1);
            branch.setClosedP1(closedP1);
        }
        if (openP1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_P).orElseThrow()
                    .addTerm(openP1);
            branch.setOpenP1(openP1);
        }
        if (p1 != null) {
            branch.setP1(p1);
        }
        if (closedQ1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_Q).orElseThrow()
                    .addTerm(closedQ1);
            branch.setClosedQ1(closedQ1);
        }
        if (openQ1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_Q).orElseThrow()
                    .addTerm(openQ1);
            branch.setOpenQ1(openQ1);
        }
        if (q1 != null) {
            branch.setQ1(q1);
        }
        if (closedP2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_P).orElseThrow()
                    .addTerm(closedP2);
            branch.setClosedP2(closedP2);
        }
        if (openP2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_P).orElseThrow()
                    .addTerm(openP2);
            branch.setOpenP2(openP2);
        }
        if (p2 != null) {
            branch.setP2(p2);
        }
        if (closedQ2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_Q).orElseThrow()
                    .addTerm(closedQ2);
            branch.setClosedQ2(closedQ2);
        }
        if (openQ2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_Q).orElseThrow()
                    .addTerm(openQ2);
            branch.setOpenQ2(openQ2);
        }
        if (q2 != null) {
            branch.setQ2(q2);
        }

        if (closedI1 != null) {
            equationSystem.attach(closedI1);
            branch.setClosedI1(closedI1);
        }
        if (openI1 != null) {
            equationSystem.attach(openI1);
            branch.setOpenI1(openI1);
        }
        if (i1 != null) {
            branch.setI1(i1);
        }

        if (closedI2 != null) {
            equationSystem.attach(closedI2);
            branch.setClosedI2(closedI2);
        }
        if (openI2 != null) {
            equationSystem.attach(openI2);
            branch.setOpenI2(openI2);
        }
        if (i2 != null) {
            branch.setI2(i2);
        }
    }

    static void updateBranchEquations(LfBranch branch) {
        if (!branch.isDisabled() && !branch.isZeroImpedance(LoadFlowModel.AC)) {
            if (branch.isConnectedSide1() && branch.isConnectedSide2()) {
                setActive(branch.getOpenP1(), false);
                setActive(branch.getOpenQ1(), false);
                setActive(branch.getClosedP1(), true);
                setActive(branch.getClosedQ1(), true);
                setActive(branch.getOpenP2(), false);
                setActive(branch.getOpenQ2(), false);
                setActive(branch.getClosedP2(), true);
                setActive(branch.getClosedQ2(), true);
                branch.getAdditionalOpenP1().forEach(openP1 -> setActive(openP1, false));
                branch.getAdditionalOpenQ1().forEach(openQ1 -> setActive(openQ1, false));
                branch.getAdditionalClosedP1().forEach(closedP1 -> setActive(closedP1, true));
                branch.getAdditionalClosedQ1().forEach(closedQ1 -> setActive(closedQ1, true));
                branch.getAdditionalOpenP2().forEach(openP2 -> setActive(openP2, false));
                branch.getAdditionalOpenQ2().forEach(openQ2 -> setActive(openQ2, false));
                branch.getAdditionalClosedP2().forEach(closedP2 -> setActive(closedP2, true));
                branch.getAdditionalClosedQ2().forEach(closedQ2 -> setActive(closedQ2, true));
            } else if (branch.isConnectedSide1() && !branch.isConnectedSide2()) {
                setActive(branch.getOpenP1(), true);
                setActive(branch.getOpenQ1(), true);
                setActive(branch.getClosedP1(), false);
                setActive(branch.getClosedQ1(), false);
                setActive(branch.getOpenP2(), false);
                setActive(branch.getOpenQ2(), false);
                setActive(branch.getClosedP2(), false);
                setActive(branch.getClosedQ2(), false);
                branch.getAdditionalOpenP1().forEach(openP1 -> setActive(openP1, true));
                branch.getAdditionalOpenQ1().forEach(openQ1 -> setActive(openQ1, true));
                branch.getAdditionalClosedP1().forEach(closedP1 -> setActive(closedP1, false));
                branch.getAdditionalClosedQ1().forEach(closedQ1 -> setActive(closedQ1, false));
                branch.getAdditionalOpenP2().forEach(openP2 -> setActive(openP2, false));
                branch.getAdditionalOpenQ2().forEach(openQ2 -> setActive(openQ2, false));
                branch.getAdditionalClosedP2().forEach(closedP2 -> setActive(closedP2, false));
                branch.getAdditionalClosedQ2().forEach(closedQ2 -> setActive(closedQ2, false));
            } else if (!branch.isConnectedSide1() && branch.isConnectedSide2()) {
                setActive(branch.getOpenP2(), true);
                setActive(branch.getOpenQ2(), true);
                setActive(branch.getClosedP2(), false);
                setActive(branch.getClosedQ2(), false);
                setActive(branch.getOpenP1(), false);
                setActive(branch.getOpenQ1(), false);
                setActive(branch.getClosedP1(), false);
                setActive(branch.getClosedQ1(), false);
                branch.getAdditionalOpenP1().forEach(openP1 -> setActive(openP1, false));
                branch.getAdditionalOpenQ1().forEach(openQ1 -> setActive(openQ1, false));
                branch.getAdditionalClosedP1().forEach(closedP1 -> setActive(closedP1, false));
                branch.getAdditionalClosedQ1().forEach(closedQ1 -> setActive(closedQ1, false));
                branch.getAdditionalOpenP2().forEach(openP2 -> setActive(openP2, true));
                branch.getAdditionalOpenQ2().forEach(openQ2 -> setActive(openQ2, true));
                branch.getAdditionalClosedP2().forEach(closedP2 -> setActive(closedP2, false));
                branch.getAdditionalClosedQ2().forEach(closedQ2 -> setActive(closedQ2, false));
            } else {
                setActive(branch.getOpenP1(), false);
                setActive(branch.getOpenQ1(), false);
                setActive(branch.getClosedP1(), false);
                setActive(branch.getClosedQ1(), false);
                setActive(branch.getOpenP2(), false);
                setActive(branch.getOpenQ2(), false);
                setActive(branch.getClosedP2(), false);
                setActive(branch.getClosedQ2(), false);
                branch.getAdditionalOpenP1().forEach(openP1 -> setActive(openP1, false));
                branch.getAdditionalOpenQ1().forEach(openQ1 -> setActive(openQ1, false));
                branch.getAdditionalClosedP1().forEach(closedP1 -> setActive(closedP1, false));
                branch.getAdditionalClosedQ1().forEach(closedQ1 -> setActive(closedQ1, false));
                branch.getAdditionalOpenP2().forEach(openP2 -> setActive(openP2, false));
                branch.getAdditionalOpenQ2().forEach(openQ2 -> setActive(openQ2, false));
                branch.getAdditionalClosedP2().forEach(closedP2 -> setActive(closedP2, false));
                branch.getAdditionalClosedQ2().forEach(closedQ2 -> setActive(closedQ2, false));
            }
        }
    }

    private static void createHvdcAcEmulationEquations(LfHvdc hvdc, AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        EquationTerm<AcVariableType, AcEquationType> p1 = null;
        EquationTerm<AcVariableType, AcEquationType> p2 = null;
        if (hvdc.getBus1() != null && hvdc.getBus2() != null && hvdc.isAcEmulation()) {
            p1 = new HvdcAcEmulationSide1ActiveFlowEquationTerm(hvdc, hvdc.getBus1(), hvdc.getBus2(), equationSystem.getVariableSet());
            p2 = new HvdcAcEmulationSide2ActiveFlowEquationTerm(hvdc, hvdc.getBus1(), hvdc.getBus2(), equationSystem.getVariableSet());
        } else {
            // nothing to do
        }

        if (p1 != null) {
            equationSystem.getEquation(hvdc.getBus1().getNum(), AcEquationType.BUS_TARGET_P)
                    .orElseThrow()
                    .addTerm(p1);
            hvdc.setP1(p1);
        }
        if (p2 != null) {
            equationSystem.getEquation(hvdc.getBus2().getNum(), AcEquationType.BUS_TARGET_P)
                    .orElseThrow()
                    .addTerm(p2);
            hvdc.setP2(p2);
        }
    }

    private void createImpedantBranchEquations(LfBranch branch, AcEquationSystemCreationContext creationContext) {
        branch.getAdditionalOpenP1().clear();
        branch.getAdditionalOpenQ1().clear();
        branch.getAdditionalClosedP1().clear();
        branch.getAdditionalClosedQ1().clear();
        branch.getAdditionalOpenP2().clear();
        branch.getAdditionalOpenQ2().clear();
        branch.getAdditionalClosedP2().clear();
        branch.getAdditionalClosedQ2().clear();

        // create zero and non zero impedance branch equations
        if (branch.isZeroImpedance(LoadFlowModel.AC)) {
            createNonImpedantBranch(branch, branch.getBus1(), branch.getBus2(), creationContext, branch.isSpanningTreeEdge(LoadFlowModel.AC));
        } else {
            createImpedantBranch(branch, branch.getBus1(), branch.getBus2(), creationContext);
        }
    }

    private void createBranchesEquations(AcEquationSystemCreationContext creationContext) {
        for (LfBranch branch : network.getBranches()) {
            createImpedantBranchEquations(branch, creationContext);
        }
    }

    private List<BaseEquationTerm<AcVariableType, AcEquationType>> createActiveInjectionTerms(LfBus bus,
                                                                                              AcEquationSystemCreationContext creationContext) {
        var variableSet = creationContext.getEquationSystem().getVariableSet();
        List<BaseEquationTerm<AcVariableType, AcEquationType>> terms = new ArrayList<>();
        for (LfBranch branch : bus.getBranches()) {
            if (branch.isZeroImpedance(LoadFlowModel.AC)) {
                if (branch.isSpanningTreeEdge(LoadFlowModel.AC)) {
                    EquationTerm<AcVariableType, AcEquationType> p = variableSet.getVariable(branch.getNum(), AcVariableType.DUMMY_P).createTerm();
                    if (branch.getBus2() == bus) {
                        p = p.minus();
                    }
                    terms.add(p);
                }
            } else {
                boolean deriveA1 = isDeriveA1(branch, creationParameters);
                boolean deriveR1 = isDeriveR1(branch);
                BaseEquationTerm<AcVariableType, AcEquationType> p;
                BaseEquationTerm<AcVariableType, AcEquationType> openP = null;
                if (branch.getBus1() == bus) {
                    LfBus otherSideBus = branch.getBus2();
                    if (otherSideBus != null) {
                        p = createClosedBranchSide1ActiveFlowEquationTerm(branch, bus, otherSideBus, deriveA1, deriveR1, creationContext);
                        branch.addAdditionalClosedP1(p);
                        if (branch.isDisconnectionAllowedSide2()) {
                            openP = createOpenBranchSide2ActiveFlowEquationTerm(branch, bus, creationContext);
                            branch.addAdditionalOpenP1(openP);
                        }
                    } else {
                        p = createOpenBranchSide2ActiveFlowEquationTerm(branch, bus, creationContext);
                        branch.addAdditionalOpenP1(p);
                    }
                } else {
                    LfBus otherSideBus = branch.getBus1();
                    if (otherSideBus != null) {
                        p = createClosedBranchSide2ActiveFlowEquationTerm(branch, otherSideBus, bus, deriveA1, deriveR1, creationContext);
                        branch.addAdditionalClosedP2(p);
                        if (branch.isDisconnectionAllowedSide1()) {
                            openP = createOpenBranchSide1ActiveFlowEquationTerm(branch, bus, creationContext);
                            branch.addAdditionalOpenP2(openP);
                        }
                    } else {
                        p = createOpenBranchSide1ActiveFlowEquationTerm(branch, bus, creationContext);
                        branch.addAdditionalOpenP2(p);
                    }
                }
                terms.add(p);
                if (openP != null) {
                    terms.add(openP);
                }
            }
        }
        return terms;
    }

    private void createMultipleSlackBusesEquations(AcEquationSystemCreationContext creationContext) {
        var equationSystem = creationContext.getEquationSystem();
        List<LfBus> slackBuses = network.getSlackBuses();
        if (slackBuses.size() > 1) {
            LfBus firstSlackBus = slackBuses.get(0);
            for (int i = 1; i < slackBuses.size(); i++) {
                LfBus slackBus = slackBuses.get(i);
                // example for 3 slack buses
                // target_p2 - target_p1 = slack_p2 - slack_p1
                // target_p3 - target_p1 = slack_p3 - slack_p1
                equationSystem.createEquation(slackBus, AcEquationType.BUS_DISTR_SLACK_P)
                        .addTerms(createActiveInjectionTerms(firstSlackBus, creationContext).stream()
                                .map(BaseEquationTerm::minus)
                                .toList())
                        .addTerms(createActiveInjectionTerms(slackBus, creationContext));
                // to update open/close terms activation
                for (LfBranch branch : slackBus.getBranches()) {
                    updateBranchEquations(branch);
                }
            }
        }
    }

    protected void create(AcEquationSystemCreationContext creationContext) {
        createBusesEquations(creationContext);
        createMultipleSlackBusesEquations(creationContext);
        createBranchesEquations(creationContext);

        for (LfHvdc hvdc : network.getHvdcs()) {
            createHvdcAcEmulationEquations(hvdc, creationContext);
        }

        createVoltageControlEquations(creationContext);

        var equationSystem = creationContext.getEquationSystem();
        EquationSystemPostProcessor.findAll().forEach(pp -> pp.onCreate(equationSystem));

        network.addListener(LfNetworkListenerTracer.trace(new AcEquationSystemUpdater(this, creationContext)));
    }

    public EquationSystem<AcVariableType, AcEquationType> create() {
        EquationSystem<AcVariableType, AcEquationType> equationSystem = new EquationSystem<>(AcEquationType.class, network);
        AcEquationSystemCreationContext creationContext = new AcEquationSystemCreationContext(equationSystem);
        create(creationContext);
        return equationSystem;
    }
}
