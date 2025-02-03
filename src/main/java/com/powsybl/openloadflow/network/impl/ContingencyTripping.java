/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.math.graph.TraverseResult;

import java.util.*;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
public class ContingencyTripping {

    private static final ContingencyTripping NO_OP_TRIPPING = new ContingencyTripping(Collections.emptyList(), (s, tt, nt, nbv) -> null);

    @FunctionalInterface
    private interface NodeBreakerTraverserFactory {
        VoltageLevel.NodeBreakerView.TopologyTraverser create(
                Set<Switch> stoppingSwitches, Set<Terminal> traversedTerminals, List<Terminal> neighbourTerminals,
                VoltageLevel.NodeBreakerView nodeBreakerView);
    }

    private final List<? extends Terminal> terminals;
    private final NodeBreakerTraverserFactory nodeBreakerTraverserFactory;

    public ContingencyTripping(List<? extends Terminal> terminals, NodeBreakerTraverserFactory nodeBreakerTraverserFactory) {
        this.terminals = terminals;
        this.nodeBreakerTraverserFactory = nodeBreakerTraverserFactory;
    }

    public ContingencyTripping(Terminal terminal, NodeBreakerTraverserFactory nodeBreakerTraverserFactory) {
        this(List.of(terminal), nodeBreakerTraverserFactory);
    }

    public static ContingencyTripping createBranchTripping(Network network, Branch<?> branch) {
        return createBranchTripping(network, branch, null);
    }

    public static ContingencyTripping createBranchTripping(Network network, Branch<?> branch, String voltageLevelId) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(branch);

        if (voltageLevelId != null) {
            if (voltageLevelId.equals(branch.getTerminal1().getVoltageLevel().getId())) {
                return new ContingencyTripping(branch.getTerminal1(), NodeBreakerTraverser::new);
            } else if (voltageLevelId.equals(branch.getTerminal2().getVoltageLevel().getId())) {
                return new ContingencyTripping(branch.getTerminal2(), NodeBreakerTraverser::new);
            } else {
                throw new PowsyblException("VoltageLevel '" + voltageLevelId + "' not connected to branch '" + branch.getId() + "'");
            }
        } else {
            return new ContingencyTripping(List.of(branch.getTerminal1(), branch.getTerminal2()), NodeBreakerTraverser::new);
        }
    }

    public static ContingencyTripping createInjectionTripping(Network network, Injection<?> injection) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(injection);

        return new ContingencyTripping(injection.getTerminal(), NodeBreakerTraverser::new);
    }

    public static ContingencyTripping createThreeWindingsTransformerTripping(Network network, ThreeWindingsTransformer twt) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(twt);

        return new ContingencyTripping(twt.getTerminals(), NodeBreakerTraverser::new);
    }

    public static ContingencyTripping createBusbarSectionMinimalTripping(Network network, BusbarSection bbs) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(bbs);

        NodeBreakerTraverserFactory minimalTraverserFactory = (stoppingSwitches, traversedTerminals, neighbourTerminals, nbv) ->
            // To have the minimal tripping ("no propagation") with a busbar section we still need to traverse the
            // voltage level starting from that busbar section, stopping at first switch encountered (which will be
            // marked as retained afterwards), in order to have the smallest lost bus in breaker view
            // Note that neighbourTerminals is not filled up: we don't want to propagate to neighbouring voltage levels as
            // this is a minimal tripping ("no propagation")
            (nodeBefore, sw, nodeAfter) -> {
                if (sw != null) {
                    if (!sw.isOpen()) {
                        stoppingSwitches.add(sw);
                    }
                    return TraverseResult.TERMINATE_PATH;
                } else {
                    nbv.getOptionalTerminal(nodeAfter).ifPresent(traversedTerminals::add);
                    return TraverseResult.CONTINUE;
                }
            };
        return new ContingencyTripping(bbs.getTerminal(), minimalTraverserFactory);
    }

    public static ContingencyTripping createContingencyTripping(Network network, Identifiable<?> identifiable) {
        switch (identifiable.getType()) {
            case LINE,
                 TWO_WINDINGS_TRANSFORMER,
                 TIE_LINE:
                return ContingencyTripping.createBranchTripping(network, (Branch<?>) identifiable);
            case DANGLING_LINE,
                 GENERATOR,
                 LOAD,
                 SHUNT_COMPENSATOR,
                 STATIC_VAR_COMPENSATOR,
                 BUSBAR_SECTION,
                 BATTERY:
                return ContingencyTripping.createInjectionTripping(network, (Injection<?>) identifiable);
            case THREE_WINDINGS_TRANSFORMER:
                return ContingencyTripping.createThreeWindingsTransformerTripping(network, (ThreeWindingsTransformer) identifiable);
            case HVDC_LINE,
                 SWITCH:
                return ContingencyTripping.NO_OP_TRIPPING;
            default:
                throw new UnsupportedOperationException("Unsupported contingency element type: " + identifiable.getType());
        }
    }

    public void traverse(Set<Switch> switchesToOpen, Set<Terminal> terminalsToDisconnect) {
        Set<Terminal> traversedTerminals = new HashSet<>();
        terminals.forEach(t -> traverseFromTerminal(t, switchesToOpen, traversedTerminals));
        terminalsToDisconnect.addAll(traversedTerminals);
    }

    /**
     * Recursive method to calculate the switches to open and the traversed terminals from a terminal
     * @param terminal starting terminal
     * @param switchesToOpen set of switches which would be opened by the contingency propagation from terminal
     * @param traversedTerminals set of terminals traversed by the contingency propagation
     */
    private void traverseFromTerminal(Terminal terminal, Set<Switch> switchesToOpen, Set<Terminal> traversedTerminals) {
        Objects.requireNonNull(terminal);
        Objects.requireNonNull(switchesToOpen);
        Objects.requireNonNull(traversedTerminals);

        if (traversedTerminals.contains(terminal)) {
            return;
        }

        if (terminal.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            traversedTerminals.add(terminal);
            List<Terminal> neighbourTerminals = traverseNodeBreakerVoltageLevelsFromTerminal(terminal, switchesToOpen, traversedTerminals);

            // Recursive call to continue the traverser in affected neighbouring voltage levels
            neighbourTerminals.forEach(t -> traverseFromTerminal(t, switchesToOpen, traversedTerminals));
        } else {
            // In bus breaker view we have no idea what kind of switch it was in the initial node/breaker topology
            // so to keep things simple we do not propagate the fault
            if (terminal.isConnected()) {
                traversedTerminals.add(terminal);
            }
        }
    }

    private List<Terminal> traverseNodeBreakerVoltageLevelsFromTerminal(Terminal terminal, Set<Switch> switchesToOpen,
                                                                        Set<Terminal> traversedTerminals) {
        int initNode = terminal.getNodeBreakerView().getNode();
        VoltageLevel.NodeBreakerView nodeBreakerView = terminal.getVoltageLevel().getNodeBreakerView();

        List<Terminal> neighbourTerminals = new ArrayList<>();
        VoltageLevel.NodeBreakerView.TopologyTraverser traverser = nodeBreakerTraverserFactory.create(
                switchesToOpen, traversedTerminals, neighbourTerminals, nodeBreakerView);
        nodeBreakerView.traverse(initNode, traverser);

        return neighbourTerminals;
    }

}
