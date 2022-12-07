/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.commons.PowsyblException;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.DiscretePhaseControl.Mode;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public final class AcEquationSystem {

    private AcEquationSystem() {
    }

    private static void createBusEquation(LfBus bus,
                                          EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                          AcEquationSystemCreationParameters creationParameters) {
        var p = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P);
        bus.setP(p);
        var q = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q);
        bus.setQ(q);

        if (bus.isSlack()) {
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_PHI)
                    .addTerm(equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_PHI)
                                           .createTerm());
            p.setActive(false);
        }

        createGeneratorControlEquations(bus, equationSystem, creationParameters);

        createShuntEquations(bus, equationSystem);

        createTransformerVoltageControlEquations(bus, equationSystem);

        createShuntVoltageControlEquations(bus, equationSystem);

        // maybe to fix later, but there is so part of OLF (like sensitivity) that needs a voltage target equation
        // deactivated
        if (!equationSystem.hasEquation(bus.getNum(), AcEquationType.BUS_TARGET_V)) {
            EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V).createTerm();
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V)
                    .addTerm(vTerm)
                    .setActive(false);
            bus.setCalculatedV(vTerm);
        }
    }

    private static void createBusesEquations(LfNetwork network,
                                             EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                             AcEquationSystemCreationParameters creationParameters) {
        for (LfBus bus : network.getBuses()) {
            createBusEquation(bus, equationSystem, creationParameters);
        }
    }

    private static void createGeneratorControlEquations(LfBus bus,
                                                        EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                        AcEquationSystemCreationParameters creationParameters) {
        bus.getVoltageControl()
                .ifPresent(voltageControl -> {
                    if (bus.isVoltageControlled()) {
                        if (voltageControl.isVoltageControlLocal()) {
                            createLocalVoltageControlEquation(bus, equationSystem, creationParameters);
                        } else {
                            createRemoteVoltageControlEquations(voltageControl, equationSystem, creationParameters);
                        }
                        updateGeneratorVoltageControl(voltageControl, equationSystem);
                    }
                });
    }

    private static void createLocalVoltageControlEquation(LfBus bus,
                                                          EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                          AcEquationSystemCreationParameters creationParameters) {
        EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V)
                .createTerm();
        bus.setCalculatedV(vTerm);
        if (bus.hasGeneratorsWithSlope()) {
            // take first generator with slope: network loading ensures that there's only one generator with slope
            double slope = bus.getGeneratorsControllingVoltageWithSlope().get(0).getSlope();

            // we only support one generator controlling voltage with a non zero slope at a bus.
            // equation is: V + slope * qSVC = targetV
            // which is modeled here with: V + slope * (sum_branch qBranch) = TargetV - slope * qLoads + slope * qGenerators
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V_WITH_SLOPE)
                    .addTerm(vTerm)
                    .addTerms(createReactiveTerms(bus, equationSystem.getVariableSet(), creationParameters)
                            .stream()
                            .map(term -> term.multiply(slope))
                            .collect(Collectors.toList()));
        } else {
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V)
                    .addTerm(vTerm);
        }

        equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q);
    }

    private static void createReactivePowerControlBranchEquation(LfBranch branch, LfBus bus1, LfBus bus2, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                                 boolean deriveA1, boolean deriveR1) {
        if (bus1 != null && bus2 != null) {
            branch.getReactivePowerControl().ifPresent(rpc -> {
                EquationTerm<AcVariableType, AcEquationType> q = rpc.getControlledSide() == ReactivePowerControl.ControlledSide.ONE
                        ? new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1)
                        : new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
                equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_Q)
                        .addTerm(q);

                // if bus has both voltage and remote reactive power controls, then only voltage control has been kept
                equationSystem.createEquation(rpc.getControllerBus(), AcEquationType.BUS_TARGET_Q);

                updateReactivePowerControlBranchEquations(rpc, equationSystem);
            });
        }
    }

    public static void updateReactivePowerControlBranchEquations(ReactivePowerControl reactivePowerControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        equationSystem.getEquation(reactivePowerControl.getControlledBranch().getNum(), AcEquationType.BRANCH_TARGET_Q)
                .orElseThrow()
                .setActive(!reactivePowerControl.getControllerBus().isDisabled()
                        && !reactivePowerControl.getControlledBranch().isDisabled());
        equationSystem.getEquation(reactivePowerControl.getControllerBus().getNum(), AcEquationType.BUS_TARGET_Q)
                .orElseThrow()
                .setActive(false);
    }

    private static void createShuntEquations(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        bus.getShunt().ifPresent(shunt -> {
            ShuntCompensatorReactiveFlowEquationTerm q = new ShuntCompensatorReactiveFlowEquationTerm(shunt, bus, equationSystem.getVariableSet(), false);
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q).addTerm(q);
            ShuntCompensatorActiveFlowEquationTerm p = new ShuntCompensatorActiveFlowEquationTerm(shunt, bus, equationSystem.getVariableSet());
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P).addTerm(p);
        });
        bus.getControllerShunt().ifPresent(shunt -> {
            ShuntCompensatorReactiveFlowEquationTerm q = new ShuntCompensatorReactiveFlowEquationTerm(shunt, bus, equationSystem.getVariableSet(), shunt.hasVoltageControlCapability());
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q).addTerm(q);
            ShuntCompensatorActiveFlowEquationTerm p = new ShuntCompensatorActiveFlowEquationTerm(shunt, bus, equationSystem.getVariableSet());
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P).addTerm(p);
        });
    }

    private static void createRemoteVoltageControlEquations(VoltageControl voltageControl,
                                                            EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                            AcEquationSystemCreationParameters creationParameters) {
        LfBus controlledBus = voltageControl.getControlledBus();

        // create voltage equation at voltage controlled bus
        EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(controlledBus.getNum(), AcVariableType.BUS_V).createTerm();
        equationSystem.createEquation(controlledBus, AcEquationType.BUS_TARGET_V)
                .addTerm(vTerm);
        controlledBus.setCalculatedV(vTerm);

        for (LfBus controllerBus : voltageControl.getControllerBuses()) {
            equationSystem.createEquation(controllerBus, AcEquationType.BUS_TARGET_Q);

            // create reactive power distribution equations at voltage controller buses

            // reactive power at controller bus i (supposing this voltage control is enabled)
            // q_i = qPercent_i * sum_j(q_j) where j are all the voltage controller buses
            // 0 = qPercent_i * sum_j(q_j) - q_i
            // which can be rewritten in a more simple way
            // 0 = (qPercent_i - 1) * q_i + qPercent_i * sum_j(q_j) where j are all the voltage controller buses except i
            Equation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerBus, AcEquationType.DISTR_Q)
                    .addTerms(createReactiveTerms(controllerBus, equationSystem.getVariableSet(), creationParameters).stream()
                            .map(term -> term.multiply(() -> controllerBus.getRemoteVoltageControlReactivePercent() - 1))
                            .collect(Collectors.toList()));
            for (LfBus otherControllerBus : voltageControl.getControllerBuses()) {
                if (otherControllerBus != controllerBus) {
                    zero.addTerms(createReactiveTerms(otherControllerBus, equationSystem.getVariableSet(), creationParameters).stream()
                            .map(term -> term.multiply(controllerBus::getRemoteVoltageControlReactivePercent))
                            .collect(Collectors.toList()));
                }
            }
        }
    }

    static void updateRemoteVoltageControlEquations(VoltageControl voltageControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        // ensure reactive keys are up-to-date
        voltageControl.updateReactiveKeys();

        List<LfBus> controllerBuses = voltageControl.getControllerBuses()
                .stream()
                .filter(b -> !b.isDisabled()) // discard disabled controller buses
                .collect(Collectors.toList());

        Equation<AcVariableType, AcEquationType> vEq = equationSystem.getEquation(voltageControl.getControlledBus().getNum(), AcEquationType.BUS_TARGET_V)
                .orElseThrow();

        if (voltageControl.getControlledBus().isDisabled()) {
            // if controlled bus is disabled, we disable all voltage control equations
            vEq.setActive(false);
            for (LfBus controllerBus : controllerBuses) {
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.DISTR_Q)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(true);
            }
        } else {
            List<LfBus> enabledControllerBuses = controllerBuses.stream()
                    .filter(LfBus::isVoltageControlEnabled).collect(Collectors.toList());
            List<LfBus> disabledControllerBuses = controllerBuses.stream()
                    .filter(Predicate.not(LfBus::isVoltageControlEnabled)).collect(Collectors.toList());

            // activate voltage control at controlled bus only if at least one controller bus is enabled
            vEq.setActive(!enabledControllerBuses.isEmpty());

            // deactivate reactive power distribution equation on all disabled (PQ) buses
            for (LfBus controllerBus : disabledControllerBuses) {
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.DISTR_Q)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(true);
            }

            // activate reactive power distribution equation at all enabled controller buses except one (first)
            for (int i = 0; i < enabledControllerBuses.size(); i++) {
                boolean active = i != 0;
                LfBus controllerBus = enabledControllerBuses.get(i);
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.DISTR_Q)
                        .orElseThrow()
                        .setActive(active);
                equationSystem.getEquation(controllerBus.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(false);
            }
        }
    }

    private static List<EquationTerm<AcVariableType, AcEquationType>> createReactiveTerms(LfBus controllerBus,
                                                                                          VariableSet<AcVariableType> variableSet,
                                                                                          AcEquationSystemCreationParameters creationParameters) {
        List<EquationTerm<AcVariableType, AcEquationType>> terms = new ArrayList<>();
        for (LfBranch branch : controllerBus.getBranches()) {
            EquationTerm<AcVariableType, AcEquationType> q;
            if (branch.isZeroImpedanceBranch()) {
                if (!branch.isSpanningTreeEdge()) {
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
                    q = otherSideBus != null ? new ClosedBranchSide1ReactiveFlowEquationTerm(branch, controllerBus, otherSideBus, variableSet, deriveA1, deriveR1)
                                             : new OpenBranchSide2ReactiveFlowEquationTerm(branch, controllerBus, variableSet, deriveA1, deriveR1);
                } else {
                    LfBus otherSideBus = branch.getBus1();
                    q = otherSideBus != null ? new ClosedBranchSide2ReactiveFlowEquationTerm(branch, otherSideBus, controllerBus, variableSet, deriveA1, deriveR1)
                                             : new OpenBranchSide1ReactiveFlowEquationTerm(branch, controllerBus, variableSet, deriveA1, deriveR1);
                }
            }
            terms.add(q);
        }
        controllerBus.getShunt().ifPresent(shunt -> {
            ShuntCompensatorReactiveFlowEquationTerm q = new ShuntCompensatorReactiveFlowEquationTerm(shunt, controllerBus, variableSet, false);
            terms.add(q);
        });
        controllerBus.getControllerShunt().ifPresent(shunt -> {
            ShuntCompensatorReactiveFlowEquationTerm q = new ShuntCompensatorReactiveFlowEquationTerm(shunt, controllerBus, variableSet, false);
            terms.add(q);
        });
        return terms;
    }

    public static void updateGeneratorVoltageControl(VoltageControl voltageControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        LfBus controlledBus = voltageControl.getControlledBus();
        Set<LfBus> controllerBuses = voltageControl.getControllerBuses();

        LfBus firstControllerBus = controllerBuses.iterator().next();
        if (firstControllerBus.hasGeneratorsWithSlope()) {
            // we only support one controlling static var compensator without any other controlling generators
            // we don't support controller bus that wants to control back voltage with slope.
            equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V_WITH_SLOPE)
                    .orElseThrow()
                    .setActive(firstControllerBus.isVoltageControlEnabled());
            equationSystem.getEquation(firstControllerBus.getNum(), AcEquationType.BUS_TARGET_Q)
                    .orElseThrow()
                    .setActive(!firstControllerBus.isVoltageControlEnabled());
        } else {
            if (voltageControl.isVoltageControlLocal()) {
                equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V)
                        .orElseThrow()
                        .setActive(!controlledBus.isDisabled() && controlledBus.isVoltageControlEnabled());
                equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_Q)
                        .orElseThrow()
                        .setActive(!controlledBus.isDisabled() && !controlledBus.isVoltageControlEnabled());
            } else {
                AcEquationSystem.updateRemoteVoltageControlEquations(voltageControl, equationSystem);
            }
        }
    }

    private static void createNonImpedantBranch(LfBranch branch, LfBus bus1, LfBus bus2,
                                                EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        Optional<Equation<AcVariableType, AcEquationType>> v1 = equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_V);
        Optional<Equation<AcVariableType, AcEquationType>> v2 = equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_V);
        boolean hasV1 = v1.isPresent() && v1.get().isActive(); // may be inactive if the equation has been created for sensitivity
        boolean hasV2 = v2.isPresent() && v2.get().isActive(); // may be inactive if the equation has been created for sensitivity
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
                    .addTerm(bus2vTerm.multiply(-rho));
            bus1.setCalculatedV(vTerm);
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
                    .setActive(branch.isDisabled()); // inverted logic
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
                                         .minus());

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
                    .setActive(branch.isDisabled()); // inverted logic
        } else {
            throw new IllegalStateException("Cannot happen because only there is one slack bus per model");
        }
    }

    private static void createTransformerPhaseControlEquations(LfBranch branch, LfBus bus1, LfBus bus2, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                               boolean deriveA1, boolean deriveR1) {
        if (deriveA1) {
            EquationTerm<AcVariableType, AcEquationType> a1 = equationSystem.getVariable(branch.getNum(), AcVariableType.BRANCH_ALPHA1)
                    .createTerm();
            branch.setA1(a1);
            equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_ALPHA1)
                    .addTerm(a1);
        }

        if (branch.isPhaseControlled()) {
            DiscretePhaseControl phaseControl = branch.getDiscretePhaseControl().orElseThrow();
            if (phaseControl.getMode() == Mode.CONTROLLER) {
                if (phaseControl.getUnit() == DiscretePhaseControl.Unit.A) {
                    throw new PowsyblException("Phase control in A is not yet supported");
                }

                EquationTerm<AcVariableType, AcEquationType> p = phaseControl.getControlledSide() == DiscretePhaseControl.ControlledSide.ONE
                        ? new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1)
                        : new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
                equationSystem.createEquation(branch, AcEquationType.BRANCH_TARGET_P)
                        .addTerm(p)
                        .setActive(false); // by default BRANCH_TARGET_ALPHA1 is active and BRANCH_TARGET_P inactive
            }
        }
    }

    public static void updateTransformerPhaseControlEquations(DiscretePhaseControl phaseControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        LfBranch controllerBranch = phaseControl.getController();
        LfBranch controlledBranch = phaseControl.getControlled();

        if (phaseControl.getMode() == Mode.CONTROLLER) {
            boolean enabled = !controllerBranch.isDisabled() && !controlledBranch.isDisabled();

            // activate/de-activate phase control equation
            equationSystem.getEquation(controlledBranch.getNum(), AcEquationType.BRANCH_TARGET_P)
                    .orElseThrow()
                    .setActive(enabled && controllerBranch.isPhaseControlEnabled());

            // de-activate/activate constant A1 equation
            equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_ALPHA1)
                    .orElseThrow()
                    .setActive(enabled && !controllerBranch.isPhaseControlEnabled());
        } else {
            equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_ALPHA1)
                    .orElseThrow()
                    .setActive(!controllerBranch.isDisabled());
        }
    }

    private static void createTransformerVoltageControlEquations(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        bus.getTransformerVoltageControl()
                .ifPresent(voltageControl -> {
                    // create voltage target equation at controlled bus
                    EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V)
                            .createTerm();
                    equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V).addTerm(vTerm);
                    bus.setCalculatedV(vTerm);

                    // add transformer ratio distribution equations
                    createR1DistributionEquations(voltageControl.getControllers(), equationSystem);

                    // we also create an equation per controller that will be used later to maintain R1 variable constant
                    for (LfBranch controllerBranch : voltageControl.getControllers()) {
                        equationSystem.createEquation(controllerBranch, AcEquationType.BRANCH_TARGET_RHO1)
                                .addTerm(equationSystem.getVariable(controllerBranch.getNum(), AcVariableType.BRANCH_RHO1).createTerm());
                    }

                    updateTransformerVoltageControlEquations(voltageControl, equationSystem);
                });
    }

    public static void createR1DistributionEquations(List<LfBranch> controllerBranches,
                                                     EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        for (int i = 0; i < controllerBranches.size(); i++) {
            LfBranch controllerBranch = controllerBranches.get(i);
            // r1 at controller branch i
            // r1_i = sum_j(r1_j) / controller_count where j are all the controller branches
            // 0 = sum_j(r1_j) / controller_count - r1_i
            // which can be rewritten in a more simple way
            // 0 = (1 / controller_count - 1) * r1_i + sum_j(r1_j) / controller_count where j are all the controller branches except i
            EquationTerm<AcVariableType, AcEquationType> r1 = equationSystem.getVariable(controllerBranch.getNum(), AcVariableType.BRANCH_RHO1)
                    .createTerm();
            Equation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerBranch, AcEquationType.DISTR_RHO)
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

    static void updateTransformerVoltageControlEquations(TransformerVoltageControl voltageControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        List<LfBranch> controllerBranches = voltageControl.getControllers()
                .stream()
                .filter(b -> !b.isDisabled()) // discard disabled controller branches
                .collect(Collectors.toList());

        // activate voltage target equation if control is on
        Equation<AcVariableType, AcEquationType> vEq = equationSystem.getEquation(voltageControl.getControlled().getNum(), AcEquationType.BUS_TARGET_V)
                .orElseThrow();

        if (voltageControl.getControlled().isDisabled()) {
            // if controlled bus is disabled, we deactivate voltage target and rho distribution equations
            // and activate rho target equations
            vEq.setActive(false);
            for (LfBranch controllerBranch : controllerBranches) {
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.DISTR_Q)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_RHO1)
                        .orElseThrow()
                        .setActive(true);
            }
        } else {
            List<LfBranch> enabledControllerBranches = controllerBranches.stream()
                    .filter(LfBranch::isVoltageControlEnabled)
                    .collect(Collectors.toList());
            List<LfBranch> disabledControllerBranches = controllerBranches.stream()
                    .filter(Predicate.not(LfBranch::isVoltageControlEnabled))
                    .collect(Collectors.toList());

            for (LfBranch controllerBranch : disabledControllerBranches) {
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.DISTR_RHO)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_RHO1)
                        .orElseThrow()
                        .setActive(true);
            }

            vEq.setActive(!enabledControllerBranches.isEmpty());

            for (int i = 0; i < enabledControllerBranches.size(); i++) {
                LfBranch controllerBranch = enabledControllerBranches.get(i);

                // deactivate rho1 equation
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_RHO1)
                        .orElseThrow()
                        .setActive(false);

                // activate rho1 distribution equation except one
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.DISTR_RHO)
                        .orElseThrow()
                        .setActive(i < enabledControllerBranches.size() - 1);
            }
        }
    }

    private static void createShuntVoltageControlEquations(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        bus.getShuntVoltageControl()
                .ifPresent(voltageControl -> {
                    EquationTerm<AcVariableType, AcEquationType> vTerm = equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V)
                            .createTerm();
                    equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_V).addTerm(vTerm);
                    bus.setCalculatedV(vTerm);

                    // add shunt distribution equations
                    createShuntSusceptanceDistributionEquations(voltageControl.getControllers(), equationSystem);

                    for (LfShunt controllerShunt : voltageControl.getControllers()) {
                        // we also create an equation that will be used later to maintain B variable constant
                        // this equation is now inactive
                        equationSystem.createEquation(controllerShunt, AcEquationType.SHUNT_TARGET_B)
                                .addTerm(equationSystem.getVariable(controllerShunt.getNum(), AcVariableType.SHUNT_B).createTerm());
                    }

                    updateShuntVoltageControlEquations(voltageControl, equationSystem);
                });
    }

    public static void createShuntSusceptanceDistributionEquations(List<LfShunt> controllerShunts,
                                                                   EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        for (LfShunt controllerShunt : controllerShunts) {
            // shunt b at controller bus i
            // b_i = sum_j(b_j) / controller_count where j are all the controller buses
            // 0 = sum_j(b_j) / controller_count - b_i
            // which can be rewritten in a more simple way
            // 0 = (1 / controller_count - 1) * b_i + sum_j(b_j) / controller_count where j are all the controller buses except i
            EquationTerm<AcVariableType, AcEquationType> shuntB = equationSystem.getVariable(controllerShunt.getNum(), AcVariableType.SHUNT_B)
                    .createTerm();
            Equation<AcVariableType, AcEquationType> zero = equationSystem.createEquation(controllerShunt, AcEquationType.DISTR_SHUNT_B)
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

    static void updateShuntVoltageControlEquations(ShuntVoltageControl voltageControl, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        List<LfShunt> controllerShunts = voltageControl.getControllers()
                .stream()
                .filter(b -> !b.isDisabled()) // discard disabled controller shunts
                .collect(Collectors.toList());

        Equation<AcVariableType, AcEquationType> vEq = equationSystem.getEquation(voltageControl.getControlled().getNum(), AcEquationType.BUS_TARGET_V)
                .orElseThrow();

        if (voltageControl.getControlled().isDisabled()) {
            // if controlled bus is disabled, we deactivate voltage target equation and all susceptance distribution
            // equations and activate susceptance target equations
            vEq.setActive(false);
            for (LfShunt controllerShunt : controllerShunts) {
                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.DISTR_SHUNT_B)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.SHUNT_TARGET_B)
                        .orElseThrow()
                        .setActive(true);
            }
        } else {
            List<LfShunt> enabledControllerShunts = controllerShunts.stream()
                    .filter(LfShunt::isVoltageControlEnabled)
                    .collect(Collectors.toList());
            List<LfShunt> disabledControllerShunts = controllerShunts.stream()
                    .filter(Predicate.not(LfShunt::isVoltageControlEnabled))
                    .collect(Collectors.toList());

            for (LfShunt controllerShunt : disabledControllerShunts) {
                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.DISTR_SHUNT_B)
                        .orElseThrow()
                        .setActive(false);
                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.SHUNT_TARGET_B)
                        .orElseThrow()
                        .setActive(true);
            }

            vEq.setActive(!enabledControllerShunts.isEmpty());

            for (int i = 0; i < enabledControllerShunts.size(); i++) {
                LfShunt controllerShunt = enabledControllerShunts.get(i);

                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.SHUNT_TARGET_B)
                        .orElseThrow()
                        .setActive(false);

                equationSystem.getEquation(controllerShunt.getNum(), AcEquationType.DISTR_SHUNT_B)
                        .orElseThrow()
                        .setActive(i < enabledControllerShunts.size() - 1);
            }
        }
    }

    private static boolean isDeriveA1(LfBranch branch, AcEquationSystemCreationParameters creationParameters) {
        return branch.isPhaseController()
                || (creationParameters.isForceA1Var() && branch.hasPhaseControlCapability() && branch.isConnectedAtBothSides());
    }

    private static boolean isDeriveR1(LfBranch branch) {
        return branch.isVoltageController();
    }

    private static void createImpedantBranch(LfBranch branch, LfBus bus1, LfBus bus2,
                                             EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                             AcEquationSystemCreationParameters creationParameters) {
        EquationTerm<AcVariableType, AcEquationType> p1 = null;
        EquationTerm<AcVariableType, AcEquationType> q1 = null;
        EquationTerm<AcVariableType, AcEquationType> p2 = null;
        EquationTerm<AcVariableType, AcEquationType> q2 = null;
        EquationTerm<AcVariableType, AcEquationType> i1 = null;
        EquationTerm<AcVariableType, AcEquationType> i2 = null;
        boolean deriveA1 = isDeriveA1(branch, creationParameters);
        boolean deriveR1 = isDeriveR1(branch);
        if (bus1 != null && bus2 != null) {
            p1 = new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            q1 = new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            p2 = new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            q2 = new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            i1 = new ClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            i2 = new ClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
        } else if (bus1 != null) {
            p1 = new OpenBranchSide2ActiveFlowEquationTerm(branch, bus1, equationSystem.getVariableSet(), deriveA1, deriveR1);
            q1 = new OpenBranchSide2ReactiveFlowEquationTerm(branch, bus1, equationSystem.getVariableSet(), deriveA1, deriveR1);
            i1 = new OpenBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, equationSystem.getVariableSet(), deriveA1, deriveR1);
        } else if (bus2 != null) {
            p2 = new OpenBranchSide1ActiveFlowEquationTerm(branch, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            q2 = new OpenBranchSide1ReactiveFlowEquationTerm(branch, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
            i2 = new OpenBranchSide1CurrentMagnitudeEquationTerm(branch, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1);
        }

        if (p1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_P)
                    .orElseThrow()
                    .addTerm(p1);
            branch.setP1(p1);
        }
        if (q1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_Q)
                    .orElseThrow()
                    .addTerm(q1);
            branch.setQ1(q1);
        }
        if (p2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_P)
                    .orElseThrow()
                    .addTerm(p2);
            branch.setP2(p2);
        }
        if (q2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_Q)
                    .orElseThrow()
                    .addTerm(q2);
            branch.setQ2(q2);
        }

        if (i1 != null) {
            equationSystem.attach(i1);
            branch.setI1(i1);
        }

        if (i2 != null) {
            equationSystem.attach(i2);
            branch.setI2(i2);
        }

        createReactivePowerControlBranchEquation(branch, bus1, bus2, equationSystem, deriveA1, deriveR1);

        createTransformerPhaseControlEquations(branch, bus1, bus2, equationSystem, deriveA1, deriveR1);
    }

    private static void createHvdcAcEmulationEquations(LfHvdc hvdc, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        EquationTerm<AcVariableType, AcEquationType> p1 = null;
        EquationTerm<AcVariableType, AcEquationType> p2 = null;
        if (hvdc.getBus1() != null && hvdc.getBus2() != null) {
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

    private static void createBranchEquations(LfBranch branch,
                                              EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                              AcEquationSystemCreationParameters creationParameters) {
        // create zero and non zero impedance branch equations
        if (branch.isZeroImpedanceBranch()) {
            if (branch.isSpanningTreeEdge()) {
                createNonImpedantBranch(branch, branch.getBus1(), branch.getBus2(), equationSystem);
            }
        } else {
            createImpedantBranch(branch, branch.getBus1(), branch.getBus2(), equationSystem, creationParameters);
        }
    }

    private static void createBranchesEquations(LfNetwork network,
                                                EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                AcEquationSystemCreationParameters creationParameters) {
        for (LfBranch branch : network.getBranches()) {
            createBranchEquations(branch, equationSystem, creationParameters);
        }
    }

    public static EquationSystem<AcVariableType, AcEquationType> create(LfNetwork network) {
        return create(network, new AcEquationSystemCreationParameters());
    }

    public static EquationSystem<AcVariableType, AcEquationType> create(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(creationParameters);

        EquationSystem<AcVariableType, AcEquationType> equationSystem = new EquationSystem<>(true);

        createBusesEquations(network, equationSystem, creationParameters);
        createBranchesEquations(network, equationSystem, creationParameters);

        for (LfHvdc hvdc : network.getHvdcs()) {
            createHvdcAcEmulationEquations(hvdc, equationSystem);
        }

        EquationSystemPostProcessor.findAll().forEach(pp -> pp.onCreate(equationSystem));

        network.addListener(new AcEquationSystemUpdater(equationSystem));

        return equationSystem;
    }
}
