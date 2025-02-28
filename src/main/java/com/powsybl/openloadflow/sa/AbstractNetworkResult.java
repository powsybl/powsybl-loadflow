/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfLegBranch;
import com.powsybl.openloadflow.network.impl.LfStarBus;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResult;
import com.powsybl.security.results.ThreeWindingsTransformerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractNetworkResult {

    protected final LfNetwork network;

    protected final StateMonitorIndex monitorIndex;

    protected final boolean createResultExtension;

    public interface BranchResultCreator {
        List<BranchResult> create(LfBranch branch, double preContingencyBranchP1, double preContingencyBranchOfContingencyP1, boolean createExtension);
    }

    protected final BranchResultCreator branchResultsCreator;

    protected final List<BusResult> busResults = new ArrayList<>();

    protected final List<ThreeWindingsTransformerResult> threeWindingsTransformerResults = new ArrayList<>();

    protected AbstractNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension) {
        this(network, monitorIndex, createResultExtension, LfBranch::createBranchResult);
    }

    protected AbstractNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension,
                                    BranchResultCreator branchResultsCreator) {
        this.network = Objects.requireNonNull(network);
        this.monitorIndex = Objects.requireNonNull(monitorIndex);
        this.createResultExtension = createResultExtension;
        this.branchResultsCreator = Objects.requireNonNull(branchResultsCreator);
    }

    protected void addResults(StateMonitor monitor, Consumer<LfBranch> branchConsumer, Predicate<LfBranch> isDisabled) {
        Objects.requireNonNull(monitor);
        if (!monitor.getBranchIds().isEmpty()) {
            network.getBranches().stream()
                    .filter(lfBranch -> !isDisabled.test(lfBranch))
                    .forEach(lfBranch -> {
                        for (String originalId : lfBranch.getOriginalIds()) {
                            if (monitor.getBranchIds().contains(originalId)) {
                                branchConsumer.accept(lfBranch);
                                break; // only generate result at first original ID match
                            }
                        }
                    });
        }

        if (!monitor.getVoltageLevelIds().isEmpty()) {
            network.getBuses().stream()
                    .filter(lfBus -> monitor.getVoltageLevelIds().contains(lfBus.getVoltageLevelId()))
                    .filter(lfBus -> !lfBus.isDisabled())
                    .forEach(lfBus -> busResults.addAll(lfBus.createBusResults()));
        }

        if (!monitor.getThreeWindingsTransformerIds().isEmpty()) {
            monitor.getThreeWindingsTransformerIds().stream()
                    .filter(id -> network.getBusById(LfStarBus.getId(id)) != null && !network.getBusById(LfStarBus.getId(id)).isDisabled())
                    .forEach(id -> threeWindingsTransformerResults.add(LfLegBranch.createThreeWindingsTransformerResult(network, id, createResultExtension)));
        }
    }

    protected void clear() {
        busResults.clear();
        threeWindingsTransformerResults.clear();
    }

    public List<BusResult> getBusResults() {
        return busResults;
    }

    public List<ThreeWindingsTransformerResult> getThreeWindingsTransformerResults() {
        return threeWindingsTransformerResults;
    }

    public abstract List<BranchResult> getBranchResults();

    public abstract void update();
}
