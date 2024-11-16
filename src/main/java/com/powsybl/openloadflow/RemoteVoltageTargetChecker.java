/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.google.common.base.Stopwatch;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.adm.AdmittanceEquationSystem;
import com.powsybl.openloadflow.adm.AdmittanceMatrix;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jgrapht.Graph;
import org.jgrapht.graph.Pseudograph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class RemoteVoltageTargetChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteVoltageTargetChecker.class);

    private final LfNetwork network;

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    private final JacobianMatrix<AcVariableType, AcEquationType> j;

    public RemoteVoltageTargetChecker(LfNetwork network,
                                      EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                      JacobianMatrix<AcVariableType, AcEquationType> j) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.j = Objects.requireNonNull(j);
    }

    private static Graph<LfBus, LfBranch> createGraph(LfNetwork lfNetwork) {
        Graph<LfBus, LfBranch> graph = new Pseudograph<>(LfBranch.class);
        for (LfBranch branch : lfNetwork.getBranches()) {
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            if (bus1 != null && bus2 != null && !branch.isDisabled()) {
                if (!graph.containsVertex(bus1)) {
                    graph.addVertex(bus1);
                }
                if (!graph.containsVertex(bus2)) {
                    graph.addVertex(bus2);
                }
                graph.addEdge(bus1, bus2, branch);
            }
        }
        return graph;
    }

    private static Set<LfBus> exploreNeighbors(Graph<LfBus, LfBranch> graph, LfBus bus, int maxDepth) {
        var it = new BreadthFirstIterator<>(graph, bus);
        Set<LfBus> neighbors = new HashSet<>();
        while (it.hasNext()) {
            LfBus b = it.next();
            int currentDepth = it.getDepth(b);
            if (currentDepth > maxDepth) {
                break;
            }
            if (b != bus) {
                neighbors.add(b);
            }
        }
        return neighbors;
    }

    private void checkIncompatibleTargets(RemoteVoltageTargetCheckerParameters parameters,
                                          Set<LfBus> controlledBuses,
                                          RemoteVoltageTargetCheckResult result) {
        Graph<LfBus, LfBranch> graph = createGraph(network);

        var ySystem = AdmittanceEquationSystem.create(network, new VariableSet<>());
        try (var y = AdmittanceMatrix.create(ySystem, parameters.getMatrixFactory())) {
            for (LfBus controlledBus : controlledBuses) {
                Set<LfBus> neighborControlledBuses = exploreNeighbors(graph, controlledBus, parameters.getControlledBusNeighborsExplorationDepth())
                        .stream().filter(controlledBuses::contains)
                        .collect(Collectors.toSet());
                if (!neighborControlledBuses.isEmpty()) {
                    for (LfBus neighborControlledBus : neighborControlledBuses) {
                        double z = y.getZ(controlledBus, neighborControlledBus).abs();
                        double dv = Math.abs(controlledBus.getHighestPriorityTargetV().orElseThrow() - neighborControlledBus.getHighestPriorityTargetV().orElseThrow());
                        double targetVoltagePlausibility = dv / z;
                        if (targetVoltagePlausibility > parameters.getTargetVoltagePlausibilityThreshold()) {
                            result.getIncompatibleTargets().add(new RemoteVoltageTargetCheckResult.IncompatibleTarget(controlledBus, neighborControlledBus));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private EquationTerm<AcEquationType, AcEquationType> getCalculatedV(LfBus controllerBus) {
        return (EquationTerm<AcEquationType, AcEquationType>) controllerBus.getCalculatedV();
    }

    private void checkUnrealisticTargets(RemoteVoltageTargetCheckerParameters parameters,
                                         List<LfBus> generatorControlledBuses,
                                         RemoteVoltageTargetCheckResult result) {
        AcSolverUtil.initStateVector(network, equationSystem, new UniformValueVoltageInitializer());

        // calculate target voltage to calculated voltage sensibilities
        var busNumToSensiColumn = LfBus.buildIndex(new ArrayList<>(generatorControlledBuses));
        DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), generatorControlledBuses.size());
        for (LfBus controlledBus : generatorControlledBuses) {
            equationSystem.getEquation(controlledBus.getNum(), AcEquationType.BUS_TARGET_V)
                    .ifPresent(equation -> rhs.set(equation.getColumn(), busNumToSensiColumn.get(controlledBus.getNum()), 1d));
        }
        j.solveTransposed(rhs);

        for (LfBus controlledBus : generatorControlledBuses) {
            int controlledBusSensiColumn = busNumToSensiColumn.get(controlledBus.getNum());
            GeneratorVoltageControl voltageControl = controlledBus.getGeneratorVoltageControl().orElseThrow();
            if (!voltageControl.isLocalControl()) {
                for (LfBus controllerBus : voltageControl.getControllerElements()) {
                    var term = getCalculatedV(controllerBus);
                    double sensiVv = term.calculateSensi(rhs, controlledBusSensiColumn);
                    // this is the targe voltage shift from a normal value (1 pu)
                    double dvControlled = voltageControl.getTargetValue() - 1.0;
                    // thanks to the sensitivity, compute the corresponding controller bus side
                    // resulting calculated voltage
                    double dvController = dvControlled * sensiVv;
                    // check if not too far from 1 pu
                    if (Math.abs(dvController) > parameters.getControllerBusAcceptableVoltageShift()) {
                        result.getUnrealisticTargets().add(new RemoteVoltageTargetCheckResult.UnrealisticTarget(controllerBus));
                    }
                }
            }
        }
    }

    public RemoteVoltageTargetCheckResult check(RemoteVoltageTargetCheckerParameters parameters) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        RemoteVoltageTargetCheckResult result = new RemoteVoltageTargetCheckResult();

        // controlled buses whatever the voltage control type it is
        Set<LfBus> controlledBuses = network.getBuses().stream()
                .filter(bus -> !bus.isDisabled()
                        && bus.isVoltageControlled()
                        && bus.getVoltageControls().stream().anyMatch(vc -> !vc.isDisabled()))
                .collect(Collectors.toSet());
        checkIncompatibleTargets(parameters, controlledBuses, result);

        // for this check we only keep generator voltage controls
        List<LfBus> generatorControlledBuses = controlledBuses.stream()
                .filter(LfBus::isGeneratorVoltageControlled)
                .toList();
        checkUnrealisticTargets(parameters, generatorControlledBuses, result);

        LOGGER.debug("Remote voltage targets checked in {} ms", stopwatch.elapsed().toMillis());

        return result;
    }

    public void fix(RemoteVoltageTargetCheckerParameters parameters) {
        RemoteVoltageTargetCheckResult result = check(parameters);

        // some buses could be part of multiple target v incompatible buses couples
        // fix the most referenced ones
        Map<LfBus, MutableInt> incompatibleControlledBusRefCount = new HashMap<>();
        for (RemoteVoltageTargetCheckResult.IncompatibleTarget incompatibleTarget : result.getIncompatibleTargets()) {
            incompatibleControlledBusRefCount.computeIfAbsent(incompatibleTarget.controlledBus1(), k -> new MutableInt(0)).increment();
            incompatibleControlledBusRefCount.computeIfAbsent(incompatibleTarget.controlledBus2(), k -> new MutableInt(0)).increment();
        }
        Set<LfBus> fixedControlledBuses = new HashSet<>();
        for (RemoteVoltageTargetCheckResult.IncompatibleTarget incompatibleTarget : result.getIncompatibleTargets()) {
            LfBus controlledBus1 = incompatibleTarget.controlledBus1();
            LfBus controlledBus2 = incompatibleTarget.controlledBus2();
            if (fixedControlledBuses.contains(controlledBus1) || fixedControlledBuses.contains(controlledBus2)) {
                continue;
            }
            LfBus controlledBusToFix = incompatibleControlledBusRefCount.get(controlledBus1).intValue() > incompatibleControlledBusRefCount.get(controlledBus2).intValue()
                    ? controlledBus1 : controlledBus2;
            for (VoltageControl voltageControl : controlledBusToFix.getVoltageControls()) {
                LOGGER.warn("Controlled buses '{}' and '{}' have incompatible target voltages ({} and {}): disable voltage control of '{}'",
                        controlledBus1.getId(), controlledBus2.getId(), controlledBus1.getHighestPriorityTargetV().orElseThrow() * controlledBus1.getNominalV(),
                        controlledBus2.getHighestPriorityTargetV().orElseThrow() * controlledBus2.getNominalV(), controlledBusToFix.getId());
                for (var controllerElement : voltageControl.getControllerElements()) {
                    voltageControl.setControllerEnabled((LfElement) controllerElement, false);
                }
                fixedControlledBuses.add(controlledBusToFix);
            }
        }

        for (RemoteVoltageTargetCheckResult.UnrealisticTarget unrealisticTarget : result.getUnrealisticTargets()) {
            LfBus controllerBus = unrealisticTarget.controllerBus();
            LfBus controlledBus = controllerBus.getGeneratorVoltageControl().orElseThrow().getControlledBus();
            LOGGER.warn("Controlled bus '{}' has an unrealistic target voltage {}, causing severe controller bus '{}' voltage drop: disable controller",
                    controlledBus.getId(), controlledBus.getHighestPriorityTargetV().orElseThrow() * controlledBus.getNominalV(), controllerBus.getId());
            controllerBus.setGeneratorVoltageControlEnabled(false);
        }
    }
}
