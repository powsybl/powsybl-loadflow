/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.action.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public final class LfAction {

    private static final class TapPositionChange {

        private final LfBranch branch;

        private final int value;

        private final boolean relative;

        private TapPositionChange(LfBranch branch, int value, boolean relative) {
            this.branch = Objects.requireNonNull(branch);
            this.value = value;
            this.relative = relative;
        }

        public LfBranch getBranch() {
            return branch;
        }

        public int getValue() {
            return value;
        }

        public boolean isRelative() {
            return relative;
        }
    }

    private final String id;

    private final LfBranch disabledBranch; // switch to open

    private final LfBranch enabledBranch; // switch to close

    private final TapPositionChange tapPositionChange;

    private final Map<LfBus, Pair<Double, PowerShift>> busesLoadShift;

    private LfAction(String id, LfBranch disabledBranch, LfBranch enabledBranch, TapPositionChange tapPositionChange, Map<LfBus, Pair<Double, PowerShift>> busesLoadShift) {
        this.id = Objects.requireNonNull(id);
        this.disabledBranch = disabledBranch;
        this.enabledBranch = enabledBranch;
        this.tapPositionChange = tapPositionChange;
        this.busesLoadShift = busesLoadShift;
    }

    public static Optional<LfAction> create(Action action, LfNetwork lfNetwork, Network network, boolean breakers) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(network);
        switch (action.getType()) {
            case SwitchAction.NAME:
                return create((SwitchAction) action, lfNetwork);

            case LineConnectionAction.NAME:
                return create((LineConnectionAction) action, lfNetwork);

            case PhaseTapChangerTapPositionAction.NAME:
                return create((PhaseTapChangerTapPositionAction) action, lfNetwork);

            case LoadAction.NAME:
                return create((LoadAction) action, lfNetwork, network, breakers);

            default:
                throw new UnsupportedOperationException("Unsupported action type: " + action.getType());
        }
    }

    private static Optional<LfAction> create(LoadAction action, LfNetwork lfNetwork, Network network, boolean breakers) {
        Load load = network.getLoad(action.getLoadId());
        Terminal terminal = load.getTerminal();
        Bus bus = breakers ? terminal.getBusBreakerView().getBus() : terminal.getBusView().getBus();
        if (bus != null) {
            LfBus lfBus = lfNetwork.getBusById(bus.getId());
            double activePowerShift = 0;
            double reactivePowerShift = 0;
            Optional<Double> activePowerValue = action.getActivePowerValue();
            Optional<Double> reactivePowerValue = action.getReactivePowerValue();
            if (activePowerValue.isPresent()) {
                activePowerShift = action.isRelativeValue() ? activePowerValue.get() : activePowerValue.get() - load.getP0();
            }
            if (reactivePowerValue.isPresent()) {
                reactivePowerShift = action.isRelativeValue() ? reactivePowerValue.get() : reactivePowerValue.get() - load.getQ0();
            }
            // FIXME: variable active power.
            PowerShift powerShift = new PowerShift(activePowerShift / PerUnit.SB, activePowerShift / PerUnit.SB, reactivePowerShift / PerUnit.SB);
            Map<LfBus, Pair<Double, PowerShift>> busesLoadShift = new HashMap<>();
            busesLoadShift.put(lfBus, Pair.of(load.getP0(), powerShift));
            return Optional.of(new LfAction(action.getId(), null, null, null, busesLoadShift));
        }

        return Optional.empty(); // could be in another component
    }

    private static Optional<LfAction> create(PhaseTapChangerTapPositionAction action, LfNetwork lfNetwork) {
        LfBranch branch = lfNetwork.getBranchById(action.getTransformerId()); // only two windings transformer for the moment.
        if (branch != null) {
            if (branch.getPiModel() instanceof SimplePiModel) {
                throw new UnsupportedOperationException("Phase tap changer tap connection action: only one tap in the branch {" + action.getTransformerId() + "}");
            } else {
                var tapPositionChange = new TapPositionChange(branch, action.getValue(), action.isRelativeValue());
                return Optional.of(new LfAction(action.getId(), null, null, tapPositionChange, Collections.emptyMap()));
            }
        }
        return Optional.empty(); // could be in another component
    }

    private static Optional<LfAction> create(LineConnectionAction action, LfNetwork lfNetwork) {
        LfBranch branch = lfNetwork.getBranchById(action.getLineId());
        if (branch != null) {
            if (action.isOpenSide1() && action.isOpenSide2()) {
                return Optional.of(new LfAction(action.getId(), branch, null, null, Collections.emptyMap()));
            } else {
                throw new UnsupportedOperationException("Line connection action: only open line at both sides is supported yet.");
            }
        }
        return Optional.empty(); // could be in another component
    }

    private static Optional<LfAction> create(SwitchAction action, LfNetwork lfNetwork) {
        LfBranch branch = lfNetwork.getBranchById(action.getSwitchId());
        if (branch != null) {
            LfBranch disabledBranch = null;
            LfBranch enabledBranch = null;
            if (action.isOpen()) {
                disabledBranch = branch;
            } else {
                enabledBranch = branch;
            }
            return Optional.of(new LfAction(action.getId(), disabledBranch, enabledBranch, null, Collections.emptyMap()));
        }
        return Optional.empty(); // could be in another component
    }

    public String getId() {
        return id;
    }

    public LfBranch getDisabledBranch() {
        return disabledBranch;
    }

    public LfBranch getEnabledBranch() {
        return enabledBranch;
    }

    public static void apply(List<LfAction> actions, LfNetwork network, LfContingency contingency, LoadFlowParameters.BalanceType balanceType) {
        Objects.requireNonNull(actions);
        Objects.requireNonNull(network);

        // first process connectivity part of actions
        updateConnectivity(actions, network, contingency);

        // then process remaining changes of actions
        for (LfAction action : actions) {
            action.apply(balanceType);
        }
    }

    private static void updateConnectivity(List<LfAction> actions, LfNetwork network, LfContingency contingency) {
        GraphConnectivity<LfBus, LfBranch> connectivity = network.getConnectivity();

        // re-update connectivity according to post contingency state (revert after LfContingency apply)
        connectivity.startTemporaryChanges();
        contingency.getDisabledBranches().forEach(connectivity::removeEdge);

        // update connectivity according to post action state
        connectivity.startTemporaryChanges();
        for (LfAction action : actions) {
            action.updateConnectivity(connectivity);
        }

        // add to action description buses and branches that won't be part of the main connected
        // component in post action state.
        Set<LfBus> removedBuses = connectivity.getVerticesRemovedFromMainComponent();
        removedBuses.forEach(bus -> bus.setDisabled(true));
        Set<LfBranch> removedBranches = new HashSet<>(connectivity.getEdgesRemovedFromMainComponent());
        // we should manage branches open at one side.
        for (LfBus bus : removedBuses) {
            bus.getBranches().stream().filter(b -> !b.isConnectedAtBothSides()).forEach(removedBranches::add);
        }
        removedBranches.forEach(branch -> branch.setDisabled(true));

        // add to action description buses and branches that will be part of the main connected
        // component in post action state.
        Set<LfBus> addedBuses = connectivity.getVerticesAddedToMainComponent();
        addedBuses.forEach(bus -> bus.setDisabled(false));
        Set<LfBranch> addedBranches = new HashSet<>(connectivity.getEdgesAddedToMainComponent());
        // we should manage branches open at one side.
        for (LfBus bus : addedBuses) {
            bus.getBranches().stream().filter(b -> !b.isConnectedAtBothSides()).forEach(addedBranches::add);
        }
        addedBranches.forEach(branch -> branch.setDisabled(false));

        // reset connectivity to discard post contingency connectivity and post action connectivity
        connectivity.undoTemporaryChanges();
        connectivity.undoTemporaryChanges();
    }

    public void updateConnectivity(GraphConnectivity<LfBus, LfBranch> connectivity) {
        if (disabledBranch != null && disabledBranch.getBus1() != null && disabledBranch.getBus2() != null) {
            connectivity.removeEdge(disabledBranch);
        }
        if (enabledBranch != null) {
            connectivity.addEdge(enabledBranch.getBus1(), enabledBranch.getBus2(), enabledBranch);
        }
    }

    public void apply(LoadFlowParameters.BalanceType balanceType) {
        if (tapPositionChange != null) {
            LfBranch branch = tapPositionChange.getBranch();
            int tapPosition = branch.getPiModel().getTapPosition();
            int value = tapPositionChange.getValue();
            int newTapPosition = tapPositionChange.isRelative() ? tapPosition + value : value;
            branch.getPiModel().setTapPosition(newTapPosition);
        }

        if (!busesLoadShift.isEmpty()) {
            for (var e : busesLoadShift.entrySet()) {
                LfBus bus = e.getKey();
                Double loadP0 = e.getValue().getLeft(); // initial load P0.
                PowerShift shift = e.getValue().getRight();
                double newP0 = loadP0 / PerUnit.SB + shift.getActive();
                double oldUpdatedP0 = LfContingency.getUpdatedLoadP0(bus, balanceType, loadP0 / PerUnit.SB, loadP0 / PerUnit.SB);
                double newUpdatedP0 = LfContingency.getUpdatedLoadP0(bus, balanceType, newP0, newP0);
                bus.setLoadTargetP(bus.getLoadTargetP() + newUpdatedP0 - oldUpdatedP0);
                bus.setLoadTargetQ(bus.getLoadTargetQ() + shift.getReactive());
                bus.getAggregatedLoads().setAbsVariableLoadTargetP(bus.getAggregatedLoads().getAbsVariableLoadTargetP() + Math.signum(shift.getActive()) * Math.abs(shift.getVariableActive()) * PerUnit.SB);
            }
        }
    }
}
