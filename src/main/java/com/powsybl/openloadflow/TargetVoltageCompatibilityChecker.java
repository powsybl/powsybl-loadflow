/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.google.common.base.Stopwatch;
import com.powsybl.openloadflow.adm.AdmittanceEquationSystem;
import com.powsybl.openloadflow.adm.AdmittanceMatrix;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
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
public class TargetVoltageCompatibilityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(TargetVoltageCompatibilityChecker.class);

    private final LfNetwork network;

    public TargetVoltageCompatibilityChecker(LfNetwork network) {
        this.network = Objects.requireNonNull(network);
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

    public List<Pair<LfBus, LfBus>> check(TargetVoltageCompatibilityCheckerParameters parameters) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        Graph<LfBus, LfBranch> graph = createGraph(network);

        Set<LfBus> controlledBuses = network.getBuses().stream()
                .filter(bus -> !bus.isDisabled()
                        && bus.isVoltageControlled()
                        && bus.getVoltageControls().stream().anyMatch(vc -> !vc.isDisabled()))
                .collect(Collectors.toSet());

        List<Pair<LfBus, LfBus>> incompatibleControlledBuses = new ArrayList<>();

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
                            incompatibleControlledBuses.add(Pair.of(controlledBus, neighborControlledBus));
                        }
                    }
                }
            }
        }

        LOGGER.debug("Target voltage compatibility checked in {} ms", stopwatch.elapsed().toMillis());

        return incompatibleControlledBuses;
    }

    public void fix(TargetVoltageCompatibilityCheckerParameters parameters) {
        List<Pair<LfBus, LfBus>> incompatibleControlledBuses = check(parameters);
        // some buses could be part of multiple target v incompatible buses couples
        // fix the most referenced ones
        Map<LfBus, MutableInt> incompatibleControlledBusRefCount = new HashMap<>();
        for (Pair<LfBus, LfBus> p : incompatibleControlledBuses) {
            incompatibleControlledBusRefCount.computeIfAbsent(p.getLeft(), k -> new MutableInt(0)).increment();
            incompatibleControlledBusRefCount.computeIfAbsent(p.getRight(), k -> new MutableInt(0)).increment();
        }
        Set<LfBus> fixedControlledBuses = new HashSet<>();
        for (Pair<LfBus, LfBus> p : incompatibleControlledBuses) {
            LfBus controlledBus1 = p.getLeft();
            LfBus controlledBus2 = p.getRight();
            if (fixedControlledBuses.contains(controlledBus1) || fixedControlledBuses.contains(controlledBus2)) {
                continue;
            }
            LfBus controlledBusToFix = incompatibleControlledBusRefCount.get(controlledBus1).intValue() > incompatibleControlledBusRefCount.get(controlledBus2).intValue()
                    ? controlledBus1 : controlledBus2;
            for (var voltageControl : controlledBusToFix.getVoltageControls()) {
                LOGGER.warn("Controlled buses '{}' and '{}' have incompatible target voltages ({} and {}): disable voltage control of '{}'",
                        controlledBus1.getId(), controlledBus2.getId(), controlledBus1.getHighestPriorityTargetV().orElseThrow() * controlledBus1.getNominalV(),
                        controlledBus2.getHighestPriorityTargetV().orElseThrow() * controlledBus2.getNominalV(), controlledBusToFix.getId());
                voltageControl.setDisabled(true);
                fixedControlledBuses.add(controlledBusToFix);
            }
        }
    }
}
