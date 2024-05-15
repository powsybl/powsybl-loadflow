/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.interfaces.SpanningTreeAlgorithm;
import org.jgrapht.alg.spanning.KruskalMinimumSpanningTree;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.Pseudograph;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfZeroImpedanceNetwork {

    private final LfNetwork network;

    private final LoadFlowModel loadFlowModel;

    private final Graph<LfBus, LfBranch> graph;

    private SpanningTreeAlgorithm.SpanningTree<LfBranch> spanningTree;

    public LfZeroImpedanceNetwork(LfNetwork network, LoadFlowModel loadFlowModel, Graph<LfBus, LfBranch> graph) {
        this.network = Objects.requireNonNull(network);
        this.loadFlowModel = Objects.requireNonNull(loadFlowModel);
        this.graph = Objects.requireNonNull(graph);
        for (LfBus bus : graph.vertexSet()) {
            bus.setZeroImpedanceNetwork(loadFlowModel, this);
        }
        updateSpanningTree();
        if (loadFlowModel == LoadFlowModel.AC) {
            updateVoltageControlMergeStatus();
            disableInvalidGeneratorVoltageControls();
        }
    }

    private static Graph<LfBus, LfBranch> createSubgraph(Graph<LfBus, LfBranch> graph, Set<LfBus> vertexSubset) {
        Graph<LfBus, LfBranch> subGraph = new Pseudograph<>(LfBranch.class);
        Graphs.addGraph(subGraph, new AsSubgraph<>(graph, vertexSubset));
        return subGraph;
    }

    public static Set<LfZeroImpedanceNetwork> create(LfNetwork network, LoadFlowModel loadFlowModel) {
        Objects.requireNonNull(network);
        Set<LfZeroImpedanceNetwork> zeroImpedanceNetworks = new LinkedHashSet<>();
        var graph = createZeroImpedanceSubGraph(network, loadFlowModel);
        List<Set<LfBus>> connectedSets = new ConnectivityInspector<>(graph).connectedSets();
        for (Set<LfBus> connectedSet : connectedSets) {
            var subGraph = createSubgraph(graph, connectedSet);
            zeroImpedanceNetworks.add(new LfZeroImpedanceNetwork(network, loadFlowModel, subGraph));
        }
        return zeroImpedanceNetworks;
    }

    private static Graph<LfBus, LfBranch> createZeroImpedanceSubGraph(LfNetwork network, LoadFlowModel loadFlowModel) {
        Graph<LfBus, LfBranch> subGraph = new Pseudograph<>(LfBranch.class);
        for (LfBranch branch : network.getBranches()) {
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            if (bus1 != null && bus2 != null && branch.isZeroImpedance(loadFlowModel)) {
                // add to zero impedance graph all buses that could be connected to a zero impedance branch
                if (!subGraph.containsVertex(bus1)) {
                    subGraph.addVertex(bus1);
                }
                if (!subGraph.containsVertex(bus2)) {
                    subGraph.addVertex(bus2);
                }
                if (!branch.isDisabled()) {
                    subGraph.addEdge(bus1, bus2, branch);
                }
            }
        }
        return subGraph;
    }

    public LfNetwork getNetwork() {
        return network;
    }

    public LoadFlowModel getLoadFlowModel() {
        return loadFlowModel;
    }

    public Graph<LfBus, LfBranch> getGraph() {
        return graph;
    }

    public SpanningTreeAlgorithm.SpanningTree<LfBranch> getSpanningTree() {
        return spanningTree;
    }

    public void updateSpanningTree() {
        spanningTree = new KruskalMinimumSpanningTree<>(graph).getSpanningTree();
        Set<LfBranch> spanningTreeEdges = spanningTree.getEdges();
        for (LfBranch branch : graph.edgeSet()) {
            branch.setSpanningTreeEdge(loadFlowModel, spanningTreeEdges.contains(branch));
        }
    }

    @SuppressWarnings("unchecked")
    private static void linkVoltageControls(VoltageControl<?> mainVc, VoltageControl<?> vc) {
        mainVc.mergedDependentVoltageControls.add((VoltageControl) vc);
        vc.mainMergedVoltageControl = (VoltageControl) mainVc;
    }

    private void updateVoltageControlMergeStatus() {
        Map<VoltageControl.Type, List<VoltageControl<?>>> voltageControlsByType = new EnumMap<>(VoltageControl.Type.class);
        for (LfBus zb : graph.vertexSet()) { // all enabled by design
            if (zb.isVoltageControlled()) {
                for (VoltageControl<?> vc : zb.getVoltageControls()) {
                    voltageControlsByType.computeIfAbsent(vc.getType(), k -> new ArrayList<>())
                            .add(vc);
                    vc.getMergedDependentVoltageControls().clear();
                    vc.mainMergedVoltageControl = null;
                    vc.disabled = false;
                }
            }
        }
        for (List<VoltageControl<?>> voltageControls : voltageControlsByType.values()) {
            if (voltageControls.size() > 1) {
                // we take the highest target voltage (why not...) and in case of equality the voltage control
                // with the first controlled bus ID by alpha sort
                voltageControls.sort(Comparator.<VoltageControl<?>>comparingDouble(VoltageControl::getTargetValue)
                        .reversed()
                        .thenComparing(o -> o.getControlledBus().getId()));
                VoltageControl<?> mainVc = voltageControls.get(0);
                mainVc.mergeStatus = VoltageControl.MergeStatus.MAIN;
                // first one is main, the other ones are dependents
                for (int i = 1; i < voltageControls.size(); i++) {
                    VoltageControl<?> vc = voltageControls.get(i);
                    vc.mergeStatus = VoltageControl.MergeStatus.DEPENDENT;
                    linkVoltageControls(mainVc, vc);
                }
            } else {
                voltageControls.get(0).mergeStatus = VoltageControl.MergeStatus.MAIN;
            }
        }
    }

    private void disableInvalidGeneratorVoltageControls() {
        List<LfBus> controlledBuses = new ArrayList<>(1);
        for (LfBus zb : graph.vertexSet()) {
            if (zb.isGeneratorVoltageControlEnabled()) {
                controlledBuses.add(zb.getGeneratorVoltageControl().orElseThrow().getMainVoltageControl().getControlledBus());
            }
        }
        List<LfBus> uniqueControlledBusesSortedByMaxP = controlledBuses.stream()
                .distinct()
                .sorted(Comparator.comparingDouble(LfBus::getMaxP))
                .toList();
        if (uniqueControlledBusesSortedByMaxP.size() > 1) {
            // we have an issue, just keep first one with max active power
            for (int i = 1; i < uniqueControlledBusesSortedByMaxP.size(); i++) {
                uniqueControlledBusesSortedByMaxP.get(i).getGeneratorVoltageControl().orElseThrow().setDisabled(true);
            }
        }
    }

    public void removeBranchAndTryToSplit(LfBranch disabledBranch) {
        graph.removeEdge(disabledBranch);

        List<Set<LfBus>> connectedSets = new ConnectivityInspector<>(graph).connectedSets();
        if (connectedSets.size() > 1) { // real split
            disabledBranch.setSpanningTreeEdge(loadFlowModel, false);

            Set<LfZeroImpedanceNetwork> zeroImpedanceNetworks = network.getZeroImpedanceNetworks(loadFlowModel);
            zeroImpedanceNetworks.remove(this);
            List<LfZeroImpedanceNetwork> splitZns = new ArrayList<>(2);
            for (Set<LfBus> connectedSet : connectedSets) {
                var subGraph = createSubgraph(graph, connectedSet);
                splitZns.add(new LfZeroImpedanceNetwork(network, loadFlowModel, subGraph));
            }
            zeroImpedanceNetworks.addAll(splitZns);

            for (LfNetworkListener listener : network.getListeners()) {
                listener.onZeroImpedanceNetworkSplit(this, splitZns, loadFlowModel);
            }
        } else {
            if (disabledBranch.isSpanningTreeEdge(loadFlowModel)) {
                disabledBranch.setSpanningTreeEdge(loadFlowModel, false);

                // just update the spanning
                updateSpanningTree();
            }
        }
    }

    public static void addBranchAndMerge(LfZeroImpedanceNetwork zn1, LfZeroImpedanceNetwork zn2, LfBranch enabledBranch) {
        Objects.requireNonNull(zn1);
        Objects.requireNonNull(zn2);
        Objects.requireNonNull(enabledBranch);
        LfNetwork network = zn1.getNetwork();
        LoadFlowModel loadFlowModel = zn1.getLoadFlowModel();
        Set<LfZeroImpedanceNetwork> zeroImpedanceNetworks = network.getZeroImpedanceNetworks(loadFlowModel);
        Graph<LfBus, LfBranch> mergedGraph = new Pseudograph<>(LfBranch.class);
        Graphs.addGraph(mergedGraph, zn1.getGraph());
        Graphs.addGraph(mergedGraph, zn2.getGraph());
        mergedGraph.addEdge(enabledBranch.getBus1(), enabledBranch.getBus2(), enabledBranch);
        zeroImpedanceNetworks.remove(zn1);
        zeroImpedanceNetworks.remove(zn2);
        LfZeroImpedanceNetwork mergedZn = new LfZeroImpedanceNetwork(network, loadFlowModel, mergedGraph);
        zeroImpedanceNetworks.add(mergedZn);

        for (LfNetworkListener listener : network.getListeners()) {
            listener.onZeroImpedanceNetworkMerge(zn1, zn2, mergedZn, loadFlowModel);
        }
    }

    public void addBranch(LfBranch branch) {
        graph.addEdge(branch.getBus1(), branch.getBus2(), branch);
    }

    @Override
    public String toString() {
        return "LfZeroImpedanceNetwork(loadFlowModel=" + loadFlowModel
                + ", buses=" + graph.vertexSet()
                + ", branches=" + graph.edgeSet()
                + ")";
    }
}
