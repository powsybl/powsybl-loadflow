/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.google.common.collect.Sets;
import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyElement;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.util.HvdcUtils;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gaël Macherel {@literal <gael.macherel@artelys.com>}
 */
public class PropagatedContingency {

    protected static final Logger LOGGER = LoggerFactory.getLogger(PropagatedContingency.class);

    private final Contingency contingency;

    private final int index;

    private final Set<Switch> switchesToOpen;

    private final Set<Terminal> terminalsToDisconnect;

    private final Set<String> busIdsToLose;

    private final Map<String, DisabledBranchStatus> branchIdsToOpen = new LinkedHashMap<>();

    private final Set<String> hvdcIdsToOpen = new HashSet<>();

    private final Set<String> generatorIdsToLose = new HashSet<>();

    private final Map<String, PowerShift> loadIdsToLose = new HashMap<>();

    private final Map<String, AdmittanceShift> shuntIdsToShift = new HashMap<>();

    public Contingency getContingency() {
        return contingency;
    }

    public int getIndex() {
        return index;
    }

    public Set<String> getBusIdsToLose() {
        return busIdsToLose;
    }

    public Map<String, DisabledBranchStatus> getBranchIdsToOpen() {
        return branchIdsToOpen;
    }

    public Set<String> getGeneratorIdsToLose() {
        return generatorIdsToLose;
    }

    public Map<String, PowerShift> getLoadIdsToLose() {
        return loadIdsToLose;
    }

    public PropagatedContingency(Contingency contingency, int index, Set<Switch> switchesToOpen, Set<Terminal> terminalsToDisconnect,
                                 Set<String> busIdsToLose) {
        this.contingency = Objects.requireNonNull(contingency);
        this.index = index;
        this.switchesToOpen = Objects.requireNonNull(switchesToOpen);
        this.terminalsToDisconnect = Objects.requireNonNull(terminalsToDisconnect);
        this.busIdsToLose = Objects.requireNonNull(busIdsToLose);
    }

    public static List<PropagatedContingency> createList(Network network, List<Contingency> contingencies, LfTopoConfig topoConfig,
                                                         PropagatedContingencyCreationParameters creationParameters) {
        List<PropagatedContingency> propagatedContingencies = new ArrayList<>();
        for (int index = 0; index < contingencies.size(); index++) {
            Contingency contingency = contingencies.get(index);
            PropagatedContingency propagatedContingency =
                    PropagatedContingency.create(network, contingency, index, topoConfig, creationParameters);
            propagatedContingencies.add(propagatedContingency);
            topoConfig.getSwitchesToOpen().addAll(propagatedContingency.switchesToOpen);
            topoConfig.getBusIdsToLose().addAll(propagatedContingency.busIdsToLose);
        }
        return propagatedContingencies;
    }

    private static PropagatedContingency create(Network network, Contingency contingency, int index, LfTopoConfig topoConfig,
                                                PropagatedContingencyCreationParameters creationParameters) {
        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        Set<String> busIdsToLose = new HashSet<>();
        // process elements of the contingency
        for (ContingencyElement element : contingency.getElements()) {
            Identifiable<?> identifiable = getIdentifiable(network, element);
            switch (identifiable.getType()) {
                case BUS:
                    Bus bus = (Bus) identifiable;
                    if (bus.getVoltageLevel().getTopologyKind() == TopologyKind.BUS_BREAKER) {
                        busIdsToLose.add(identifiable.getId());
                        bus.visitConnectedEquipments(new DefaultTopologyVisitor() {
                            public void visitBranch(Branch<?> branch, TwoSides side) {
                                if (side == TwoSides.ONE) {
                                    topoConfig.getBranchIdsOpenableSide1().add(branch.getId());
                                } else {
                                    topoConfig.getBranchIdsOpenableSide2().add(branch.getId());
                                }
                            }

                            @Override
                            public void visitLine(Line line, TwoSides side) {
                                visitBranch(line, side);
                            }

                            @Override
                            public void visitTwoWindingsTransformer(TwoWindingsTransformer transformer, TwoSides side) {
                                visitBranch(transformer, side);
                            }

                            @Override
                            public void visitThreeWindingsTransformer(ThreeWindingsTransformer transformer, ThreeSides side) {
                                topoConfig.getBranchIdsOpenableSide1().add(LfLegBranch.getId(transformer.getId(), side.getNum()));
                            }
                        });
                    } else {
                        throw new UnsupportedOperationException("Unsupported contingency element type " + element.getType() + ": voltage level should be in bus/breaker topology");
                    }
                    break;
                case BUSBAR_SECTION:
                    if (creationParameters.isContingencyPropagation()) {
                        ContingencyTripping.createContingencyTripping(network, identifiable).traverse(switchesToOpen, terminalsToDisconnect);
                    } else {
                        ContingencyTripping.createBusbarSectionMinimalTripping(network, (BusbarSection) identifiable).traverse(switchesToOpen, terminalsToDisconnect);
                    }
                    terminalsToDisconnect.addAll(getTerminals(identifiable));
                    break;
                case SWITCH:
                    switchesToOpen.add((Switch) identifiable);
                    break;
                default:
                    if (creationParameters.isContingencyPropagation()) {
                        ContingencyTripping.createContingencyTripping(network, identifiable).traverse(switchesToOpen, terminalsToDisconnect);
                    }
                    terminalsToDisconnect.addAll(getTerminals(identifiable));
            }
        }
        PropagatedContingency propagatedContingency = new PropagatedContingency(contingency, index, switchesToOpen, terminalsToDisconnect, busIdsToLose);
        propagatedContingency.complete(topoConfig, creationParameters);
        return propagatedContingency;
    }

    private <K> void addBranchToOpen(K branchId, DisabledBranchStatus status, Map<K, DisabledBranchStatus> branchIdsToOpen) {
        DisabledBranchStatus oldStatus = branchIdsToOpen.get(branchId);
        if (oldStatus == null) {
            branchIdsToOpen.put(branchId, status);
        } else if (status == DisabledBranchStatus.BOTH_SIDES || status != oldStatus) {
            branchIdsToOpen.put(branchId, DisabledBranchStatus.BOTH_SIDES);
        }
    }

    private void complete(LfTopoConfig topoConfig, PropagatedContingencyCreationParameters creationParameters) {
        for (Switch sw : switchesToOpen) {
            addBranchToOpen(sw.getId(), DisabledBranchStatus.BOTH_SIDES, branchIdsToOpen); // we open both sides
        }

        // process terminals disconnected, in particular process injection power shift
        for (Terminal terminal : terminalsToDisconnect) {
            Connectable<?> connectable = terminal.getConnectable();
            switch (connectable.getType()) {
                case LINE,
                     TWO_WINDINGS_TRANSFORMER:
                    Branch<?> branch = (Branch<?>) connectable;
                    if (terminal == branch.getTerminal1()) {
                        addBranchToOpen(connectable.getId(), DisabledBranchStatus.SIDE_1, branchIdsToOpen);
                        topoConfig.getBranchIdsOpenableSide1().add(connectable.getId());
                    } else {
                        addBranchToOpen(connectable.getId(), DisabledBranchStatus.SIDE_2, branchIdsToOpen);
                        topoConfig.getBranchIdsOpenableSide2().add(connectable.getId());
                    }
                    break;
                case DANGLING_LINE:
                    DanglingLine dl = (DanglingLine) connectable;
                    // as we terminal is only on network side, we open both sides in LF network
                    if (dl.isPaired()) {
                        addBranchToOpen(dl.getTieLine().orElseThrow().getId(), DisabledBranchStatus.BOTH_SIDES, branchIdsToOpen);
                    } else {
                        addBranchToOpen(dl.getId(), DisabledBranchStatus.BOTH_SIDES, branchIdsToOpen);
                    }
                    break;

                case GENERATOR,
                     STATIC_VAR_COMPENSATOR,
                     BATTERY:
                    generatorIdsToLose.add(connectable.getId());
                    break;

                case LOAD:
                    Load load = (Load) connectable;
                    loadIdsToLose.put(load.getId(), PowerShift.createPowerShift(load, creationParameters.isSlackDistributionOnConformLoad()));
                    break;

                case SHUNT_COMPENSATOR:
                    ShuntCompensator shunt = (ShuntCompensator) connectable;
                    if (creationParameters.isShuntCompensatorVoltageControlOn() && shunt.isVoltageRegulatorOn()) {
                        throw new UnsupportedOperationException("Shunt compensator '" + shunt.getId() + "' with voltage control on: not supported yet");
                    }
                    double zb = PerUnit.zb(shunt.getTerminal().getVoltageLevel().getNominalV());
                    shuntIdsToShift.put(shunt.getId(), new AdmittanceShift(shunt.getG() * zb,
                            shunt.getB() * zb));
                    break;

                case HVDC_CONVERTER_STATION:
                    // in case of a hvdc contingency, both converter station will go through this case.
                    // in case of the lost of one VSC converter station only, the transmission of active power is stopped
                    // but the other converter station, if present, keeps it voltage control if present.
                    HvdcConverterStation<?> station = (HvdcConverterStation<?>) connectable;
                    hvdcIdsToOpen.add(station.getHvdcLine().getId());
                    if (connectable instanceof VscConverterStation) {
                        generatorIdsToLose.add(connectable.getId());
                    } else {
                        LccConverterStation lcc = (LccConverterStation) connectable;
                        PowerShift lccPowerShift = new PowerShift(HvdcUtils.getConverterStationTargetP(lcc) / PerUnit.SB, 0,
                                HvdcUtils.getLccConverterStationLoadTargetQ(lcc) / PerUnit.SB);
                        loadIdsToLose.put(lcc.getId(), lccPowerShift);
                    }
                    break;

                case BUSBAR_SECTION:
                    // we don't care
                    break;

                case THREE_WINDINGS_TRANSFORMER:
                    // terminal in always by construction the side 1 of the LF branch
                    ThreeWindingsTransformer twt = (ThreeWindingsTransformer) connectable;
                    for (ThreeSides side : ThreeSides.values()) {
                        if (twt.getTerminal(side) == terminal) {
                            addBranchToOpen(LfLegBranch.getId(side, connectable.getId()), DisabledBranchStatus.SIDE_1, branchIdsToOpen);
                            topoConfig.getBranchIdsOpenableSide1().add(LfLegBranch.getId(connectable.getId(), side.getNum()));
                            break;
                        }
                    }
                    break;

                default:
                    throw new UnsupportedOperationException("Unsupported by propagation contingency element type: "
                            + connectable.getType());
            }
        }
    }

    private static List<? extends Terminal> getTerminals(Identifiable<?> identifiable) {
        if (identifiable instanceof Connectable<?>) {
            return ((Connectable<?>) identifiable).getTerminals();
        }
        if (identifiable instanceof HvdcLine hvdcLine) {
            return List.of(hvdcLine.getConverterStation1().getTerminal(), hvdcLine.getConverterStation2().getTerminal());
        }
        if (identifiable instanceof TieLine line) {
            return List.of(line.getDanglingLine1().getTerminal(), line.getDanglingLine2().getTerminal());
        }
        if (identifiable instanceof Switch) {
            return Collections.emptyList();
        }
        throw new UnsupportedOperationException("Unsupported contingency element type: " + identifiable.getType());
    }

    private static Identifiable<?> getIdentifiable(Network network, ContingencyElement element) {
        Identifiable<?> identifiable;
        String identifiableType = switch (element.getType()) {
            case BRANCH, LINE, TWO_WINDINGS_TRANSFORMER -> {
                identifiable = network.getBranch(element.getId());
                yield "Branch";
            }
            case HVDC_LINE -> {
                identifiable = network.getHvdcLine(element.getId());
                yield "HVDC line";
            }
            case DANGLING_LINE -> {
                identifiable = network.getDanglingLine(element.getId());
                yield "Dangling line";
            }
            case GENERATOR -> {
                identifiable = network.getGenerator(element.getId());
                yield "Generator";
            }
            case STATIC_VAR_COMPENSATOR -> {
                identifiable = network.getStaticVarCompensator(element.getId());
                yield "Static var compensator";
            }
            case LOAD -> {
                identifiable = network.getLoad(element.getId());
                yield "Load";
            }
            case SHUNT_COMPENSATOR -> {
                identifiable = network.getShuntCompensator(element.getId());
                yield "Shunt compensator";
            }
            case SWITCH -> {
                identifiable = network.getSwitch(element.getId());
                yield "Switch";
            }
            case THREE_WINDINGS_TRANSFORMER -> {
                identifiable = network.getThreeWindingsTransformer(element.getId());
                yield "Three windings transformer";
            }
            case BUSBAR_SECTION -> {
                identifiable = network.getBusbarSection(element.getId());
                yield "Busbar section";
            }
            case TIE_LINE -> {
                identifiable = network.getTieLine(element.getId());
                yield "Tie line";
            }
            case BUS -> {
                identifiable = network.getBusBreakerView().getBus(element.getId());
                yield "Configured bus";
            }
            case BATTERY -> {
                identifiable = network.getBattery(element.getId());
                yield "Battery";
            }
            default ->
                throw new UnsupportedOperationException("Unsupported contingency element type: " + element.getType());
        };
        if (identifiable == null) {
            throw new PowsyblException(identifiableType + " '" + element.getId() + "' not found in the network");
        }
        return identifiable;
    }

    public boolean hasNoImpact() {
        return branchIdsToOpen.isEmpty()
                && hvdcIdsToOpen.isEmpty() && generatorIdsToLose.isEmpty()
                && loadIdsToLose.isEmpty() && shuntIdsToShift.isEmpty() && busIdsToLose.isEmpty();
    }

    private static boolean isSlackBusIsolated(GraphConnectivity<LfBus, LfBranch> connectivity, LfBus slackBus) {
        // check that slack bus belongs to the largest component.
        // Largest component has always the number 0.
        int number = connectivity.getComponentNumber(slackBus);
        if (number != 0) {
            // if not main component anymore but same size as the main one, still consider it as not isolated
            // (mainly useful for unit test small networks...)
            return connectivity.getLargestConnectedComponent().size() != connectivity.getConnectedComponent(slackBus).size();
        }
        return false;
    }

    private Map<LfBranch, DisabledBranchStatus> findBranchToOpenDirectlyImpactedByContingency(LfNetwork network) {
        // we add the branches connected to buses to lose.
        Map<LfBranch, DisabledBranchStatus> branchesToOpen = branchIdsToOpen.entrySet().stream()
                .map(e -> Pair.of(network.getBranchById(e.getKey()), e.getValue()))
                .filter(e -> e.getKey() != null)
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue, (disabledBranchStatus, disabledBranchStatus2) -> {
                    throw new IllegalStateException();
                }, LinkedHashMap::new));

        busIdsToLose.stream().map(network::getBusById)
                .filter(Objects::nonNull)
                .forEach(bus -> {
                    bus.getBranches().forEach(branch -> {
                        DisabledBranchStatus status = branch.getBus1() == bus ? DisabledBranchStatus.SIDE_1 : DisabledBranchStatus.SIDE_2;
                        addBranchToOpen(branch, status, branchesToOpen);
                    });
                });

        return branchesToOpen;
    }

    public record ContingencyConnectivityLossImpact(boolean ok, int createdSynchronousComponents, Set<LfBus> busesToLost, Set<LfHvdc> hvdcsWithoutPower) {

        public static Set<LfHvdc> getHvdcsWithoutPower(LfNetwork network, Set<LfBus> busesToLost, GraphConnectivity<LfBus, LfBranch> connectivity) {
            Set<LfHvdc> hvdcsWithoutFlow = new HashSet<>();
            for (LfHvdc hvdcLine : network.getHvdcs()) {
                if (checkIsolatedBus(hvdcLine.getBus1(), hvdcLine.getBus2(), busesToLost, connectivity)
                        || checkIsolatedBus(hvdcLine.getBus2(), hvdcLine.getBus1(), busesToLost, connectivity)) {
                    hvdcsWithoutFlow.add(hvdcLine);
                }
            }
            return hvdcsWithoutFlow;
        }

    }

    private static ContingencyConnectivityLossImpact findBusesAndBranchesImpactedBecauseOfConnectivityLoss(LfNetwork network, String contingencyId, Map<LfBranch, DisabledBranchStatus> branchesToOpen, boolean relocateSlackBus) {
        // update connectivity with triggered branches of this network
        // note that this will define the main component as the one containing the first slack bus
        GraphConnectivity<LfBus, LfBranch> connectivity = network.getConnectivity();
        connectivity.startTemporaryChanges();
        try {
            branchesToOpen.keySet().stream()
                    .filter(LfBranch::isConnectedAtBothSides)
                    .forEach(connectivity::removeEdge);

            if (relocateSlackBus && isSlackBusIsolated(connectivity, network.getSlackBus())) {
                LOGGER.warn("Contingency '{}' leads to an isolated slack bus: relocate slack bus inside main component",
                        contingencyId);
                // if a contingency leads to an isolated slack bus, we need to relocate the slack bus
                // we select a new slack bus excluding buses from isolated component
                Set<LfBus> excludedBuses = Sets.difference(Set.copyOf(network.getBuses()), connectivity.getLargestConnectedComponent());
                network.setExcludedSlackBuses(excludedBuses);
                // reverse main component to the one containing the relocated slack bus
                connectivity.setMainComponentVertex(network.getSlackBus());
            }

            // add to contingency description buses and branches that won't be part of the main connected
            // component in post contingency state
            int createdSynchronousComponents = connectivity.getNbConnectedComponents() - 1;
            Set<LfBus> busesToLost = connectivity.getVerticesRemovedFromMainComponent();

            // as we know here the connectivity after contingency, we have to reset active power flow of a hvdc line
            // if one bus of the line is lost.
            Set<LfHvdc> hvdcsWithoutFlow = ContingencyConnectivityLossImpact.getHvdcsWithoutPower(network, busesToLost, connectivity);

            return new ContingencyConnectivityLossImpact(true, createdSynchronousComponents, busesToLost, hvdcsWithoutFlow);
        } finally {
            // reset connectivity to discard triggered elements
            connectivity.undoTemporaryChanges();
        }
    }

    private static boolean checkIsolatedBus(LfBus bus1, LfBus bus2, Set<LfBus> busesToLost, GraphConnectivity<LfBus, LfBranch> connectivity) {
        return busesToLost.contains(bus1) && !busesToLost.contains(bus2) && Networks.isIsolatedBusForHvdc(bus1, connectivity);
    }

    private static boolean isConnectedAfterContingencySide1(Map<LfBranch, DisabledBranchStatus> branchesToOpen, LfBranch branch) {
        DisabledBranchStatus status = branchesToOpen.get(branch);
        return status == null || status == DisabledBranchStatus.SIDE_2;
    }

    private static boolean isConnectedAfterContingencySide2(Map<LfBranch, DisabledBranchStatus> branchesToOpen, LfBranch branch) {
        DisabledBranchStatus status = branchesToOpen.get(branch);
        return status == null || status == DisabledBranchStatus.SIDE_1;
    }

    public interface ContingencyConnectivityLossImpactAnalysis {

        ContingencyConnectivityLossImpact run(LfNetwork network, String contingencyId, Map<LfBranch, DisabledBranchStatus> branchesToOpen, boolean relocateSlackBus);
    }

    public Optional<LfContingency> toLfContingency(LfNetwork network) {
        return toLfContingency(network, true, PropagatedContingency::findBusesAndBranchesImpactedBecauseOfConnectivityLoss);
    }

    public Optional<LfContingency> toLfContingency(LfNetwork network, boolean relocateSlackBus, ContingencyConnectivityLossImpactAnalysis analysis) {
        // find branch to open because of direct impact of the contingency (including propagation is activated)
        Map<LfBranch, DisabledBranchStatus> branchesToOpen = findBranchToOpenDirectlyImpactedByContingency(network);

        // find branches to open and buses to lost not directly from the contingency impact but as a consequence of
        // loss of connectivity once contingency applied on the network
        ContingencyConnectivityLossImpact connectivityLossImpact = analysis.run(network, contingency.getId(), branchesToOpen, relocateSlackBus);
        if (!connectivityLossImpact.ok) {
            return Optional.empty();
        }
        Set<LfBus> busesToLost = connectivityLossImpact.busesToLost(); // nothing else

        for (LfBus busToLost : busesToLost) {
            busToLost.getBranches()
                    .forEach(branch -> {
                        // fully disable if branch is connected to 2 buses to lost or open on the other side
                        LfBus otherSideBus;
                        boolean otherSideConnected;
                        if (branch.getBus1() == busToLost) {
                            otherSideBus = branch.getBus2();
                            otherSideConnected = branch.isConnectedSide2() && isConnectedAfterContingencySide2(branchesToOpen, branch);
                        } else {
                            otherSideBus = branch.getBus1();
                            otherSideConnected = branch.isConnectedSide1() && isConnectedAfterContingencySide1(branchesToOpen, branch);
                        }
                        if (busesToLost.contains(otherSideBus) || !otherSideConnected) {
                            addBranchToOpen(branch, DisabledBranchStatus.BOTH_SIDES, branchesToOpen);
                        }
                    });
        }

        Map<LfShunt, AdmittanceShift> shunts = new LinkedHashMap<>(1);
        for (var e : shuntIdsToShift.entrySet()) {
            LfShunt shunt = network.getShuntById(e.getKey());
            if (shunt != null) { // could be in another component
                shunts.computeIfAbsent(shunt, k -> new AdmittanceShift())
                        .add(e.getValue());
            }
        }

        Set<LfGenerator> generators = new LinkedHashSet<>(1);
        for (String generatorId : generatorIdsToLose) {
            LfGenerator generator = network.getGeneratorById(generatorId);
            if (generator != null) { // could be in another component
                generators.add(generator);
            }
        }

        Map<LfLoad, LfLostLoad> loads = new LinkedHashMap<>(1);
        for (var e : loadIdsToLose.entrySet()) {
            String loadId = e.getKey();
            PowerShift powerShift = e.getValue();
            LfLoad load = network.getLoadById(loadId);
            if (load != null) { // could be in another component
                LfLostLoad lostLoad = loads.computeIfAbsent(load, k -> new LfLostLoad());
                lostLoad.getPowerShift().add(powerShift);
                lostLoad.getOriginalIds().add(loadId);
                lostLoad.updateNotParticipatingLoadP0(load, loadId, powerShift);
            }
        }

        // find hvdc lines that are part of this network
        Set<LfHvdc> lostHvdcs = hvdcIdsToOpen.stream()
                .map(network::getHvdcById)
                .filter(Objects::nonNull) // could be in another component
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (LfHvdc hvdcLine : network.getHvdcs()) {
            if (busesToLost.contains(hvdcLine.getBus1()) || busesToLost.contains(hvdcLine.getBus2())) {
                lostHvdcs.add(hvdcLine);
            }
        }

        if (branchesToOpen.isEmpty()
                && busesToLost.isEmpty()
                && shunts.isEmpty()
                && loads.isEmpty()
                && generators.isEmpty()
                && lostHvdcs.isEmpty()
                && connectivityLossImpact.hvdcsWithoutPower().isEmpty()) {
            LOGGER.debug("Contingency '{}' has no impact", contingency.getId());
            return Optional.empty();
        }

        return Optional.of(new LfContingency(contingency.getId(), index, connectivityLossImpact.createdSynchronousComponents,
                           new DisabledNetwork(busesToLost, branchesToOpen, lostHvdcs), shunts, loads, generators,
                           connectivityLossImpact.hvdcsWithoutPower()));
    }

    /**
     * In general, a {@link PropagatedContingency} is translated to a {@link LfContingency}. During the translation,
     * cleans are performed to deal with branches connected at one side and slack bus loss. But in some fast DC computations,
     * we don't want to use {@link LfContingency} object, so a dedicated clean method directly available on a {@link PropagatedContingency}
     * is needed. It contains:
     *  - Removing branches out of this synchronous component from branches to open.
     *  - Removing branches connected at one side from branches to open.
     *  - Removing slack bus from buses lost (not supported yet).
     *  - Adding branches connected to buses lost in branches to open.
     */
    public static void cleanContingencies(LfNetwork lfNetwork, List<PropagatedContingency> contingencies) {
        for (PropagatedContingency contingency : contingencies) {
            // Elements have already been checked and found in PropagatedContingency, so there is no need to
            // check them again
            Set<String> branchesToRemove = new HashSet<>(); // branches connected to one side, or switches
            for (String branchId : contingency.getBranchIdsToOpen().keySet()) {
                LfBranch lfBranch = lfNetwork.getBranchById(branchId);
                if (lfBranch == null) {
                    branchesToRemove.add(branchId); // disconnected branch
                    continue;
                }
                if (!lfBranch.isConnectedAtBothSides()) {
                    branchesToRemove.add(branchId); // branch connected only on one side
                }
            }
            branchesToRemove.forEach(branchToRemove -> contingency.getBranchIdsToOpen().remove(branchToRemove));

            // update branches to open connected with buses in contingency. This is an approximation:
            // these branches are indeed just open at one side.
            String slackBusId = null;
            for (String busId : contingency.getBusIdsToLose()) {
                LfBus bus = lfNetwork.getBusById(busId);
                if (bus != null) {
                    if (bus.isSlack()) {
                        // slack bus disabling is not supported in DC because the relocation is done from propagated contingency
                        // to LfContingency
                        // we keep the slack bus enabled and the connected branches
                        LOGGER.error("Contingency '{}' leads to the loss of a slack bus: slack bus kept", contingency.getContingency().getId());
                        slackBusId = busId;
                    } else {
                        bus.getBranches().forEach(branch -> contingency.getBranchIdsToOpen().put(branch.getId(), DisabledBranchStatus.BOTH_SIDES));
                    }
                }
            }
            if (slackBusId != null) {
                contingency.getBusIdsToLose().remove(slackBusId);
            }

            if (contingency.hasNoImpact()) {
                LOGGER.warn("Contingency '{}' has no impact", contingency.getContingency().getId());
            }
        }
    }
}
