/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.action;

import com.powsybl.action.Action;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.network.*;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 * @author Jean-Luc Bouchot {@literal <jlbouchot at gmail.com>}
 */
public abstract class AbstractLfBranchAction<A extends Action> extends AbstractLfAction<A> {

    private LfBranch disabledBranch = null; // switch to open

    private LfBranch enabledBranch = null; // switch to close

    AbstractLfBranchAction(String id, A action, LfNetwork lfNetwork) {
        super(id, action);
        findEnabledDisabledBranches(lfNetwork);
    }

    protected void setDisabledBranch(LfBranch disabledBranch) {
        this.disabledBranch = disabledBranch;
    }

    protected void setEnabledBranch(LfBranch enabledBranch) {
        this.enabledBranch = enabledBranch;
    }

    public LfBranch getDisabledBranch() {
        return this.disabledBranch;
    }

    public LfBranch getEnabledBranch() {
        return this.enabledBranch;
    }

    abstract void findEnabledDisabledBranches(LfNetwork lfNetwork);

    /**
     * Standalone apply
     */
    @Override
    public boolean apply(LfNetwork network, LfContingency contingency, LfNetworkParameters networkParameters) {
        boolean found = disabledBranch != null || enabledBranch != null;
        if (!found) {
            return false;
        }
        GraphConnectivity<LfBus, LfBranch> connectivity = network.getConnectivity();

        // re-update connectivity according to post contingency state (revert after LfContingency apply)
        connectivity.startTemporaryChanges();
        if (contingency != null) {
            contingency.getDisabledNetwork().getBranches().forEach(connectivity::removeEdge);
        }

        // update connectivity according to post action state
        connectivity.startTemporaryChanges();

        updateConnectivity(connectivity);
        updateBusesAndBranchStatus(connectivity);

        // reset connectivity to discard post contingency connectivity and post action connectivity
        connectivity.undoTemporaryChanges();
        connectivity.undoTemporaryChanges();
        return true;
    }

    /**
     * Optimized apply on an existing connectivity (to apply several branch actions at the same time)
     */
    public boolean applyOnConnectivity(GraphConnectivity<LfBus, LfBranch> connectivity) {
        boolean found = disabledBranch != null || enabledBranch != null;
        updateConnectivity(connectivity);
        return found;
    }

    private void updateConnectivity(GraphConnectivity<LfBus, LfBranch> connectivity) {
        if (disabledBranch != null && disabledBranch.getBus1() != null && disabledBranch.getBus2() != null) {
            connectivity.removeEdge(disabledBranch);
        }
        if (enabledBranch != null) {
            connectivity.addEdge(enabledBranch.getBus1(), enabledBranch.getBus2(), enabledBranch);
        }
    }

    public static void updateBusesAndBranchStatus(GraphConnectivity<LfBus, LfBranch> connectivity) {
        // disable buses and branches that won't be part of the main connected component
        Set<LfBus> removedBuses = connectivity.getVerticesRemovedFromMainComponent();
        removedBuses.forEach(bus -> bus.setDisabled(true));
        Set<LfBranch> removedBranches = new HashSet<>(connectivity.getEdgesRemovedFromMainComponent());
        // we should manage branches open at one side.
        for (LfBus bus : removedBuses) {
            bus.getBranches().stream().filter(b -> !b.isConnectedAtBothSides()).forEach(removedBranches::add);
        }
        removedBranches.forEach(branch -> branch.setDisabled(true));

        // enable buses and branches that will be part of the main connected component
        Set<LfBus> addedBuses = connectivity.getVerticesAddedToMainComponent();
        addedBuses.forEach(bus -> bus.setDisabled(false));
        Set<LfBranch> addedBranches = new HashSet<>(connectivity.getEdgesAddedToMainComponent());
        // we should manage branches open at one side.
        for (LfBus bus : addedBuses) {
            bus.getBranches().stream().filter(b -> !b.isConnectedAtBothSides()).forEach(addedBranches::add);
        }
        addedBranches.forEach(branch -> branch.setDisabled(false));
    }
}
