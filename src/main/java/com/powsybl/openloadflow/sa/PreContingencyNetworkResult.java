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
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PreContingencyNetworkResult extends AbstractNetworkResult {

    private final Map<String, BranchResult> branchResults = new HashMap<>();

    public PreContingencyNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension) {
        super(network, monitorIndex, createResultExtension);
    }

    public PreContingencyNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension,
                                       BranchResultCreator branchResultsCreator) {
        super(network, monitorIndex, createResultExtension, branchResultsCreator);
    }

    @Override
    protected void clear() {
        super.clear();
        branchResults.clear();
    }

    private void addResults(StateMonitor monitor, Predicate<LfBranch> isDisabled) {
        addResults(monitor, branch -> {
            branchResultsCreator.create(branch, Double.NaN, Double.NaN, createResultExtension)
                    .forEach(branchResult -> branchResults.put(branchResult.getBranchId(), branchResult));
        }, isDisabled);
    }

    @Override
    public void update() {
        update(LfBranch::isDisabled);
    }

    public void update(Predicate<LfBranch> isDisabled) {
        clear();
        addResults(monitorIndex.getNoneStateMonitor(), isDisabled);
        addResults(monitorIndex.getAllStateMonitor(), isDisabled);
    }

    public BranchResult getBranchResult(String branchId) {
        Objects.requireNonNull(branchId);
        return branchResults.get(branchId);
    }

    @Override
    public List<BranchResult> getBranchResults() {
        return new ArrayList<>(branchResults.values());
    }
}
