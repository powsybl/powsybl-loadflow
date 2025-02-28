/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyElement;
import com.powsybl.contingency.ContingencyElementType;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PostContingencyNetworkResult extends AbstractNetworkResult {

    private final List<BranchResult> branchResults = new ArrayList<>();

    private final PreContingencyNetworkResult preContingencyMonitorInfos;

    private final Contingency contingency;

    public PostContingencyNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension,
                                        PreContingencyNetworkResult preContingencyMonitorInfos, Contingency contingency) {
        super(network, monitorIndex, createResultExtension);
        this.preContingencyMonitorInfos = Objects.requireNonNull(preContingencyMonitorInfos);
        this.contingency = Objects.requireNonNull(contingency);
    }

    public PostContingencyNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension,
                                        BranchResultCreator branchResultsCreator, PreContingencyNetworkResult preContingencyMonitorInfos,
                                        Contingency contingency) {
        super(network, monitorIndex, createResultExtension, branchResultsCreator);
        this.preContingencyMonitorInfos = Objects.requireNonNull(preContingencyMonitorInfos);
        this.contingency = Objects.requireNonNull(contingency);
    }

    @Override
    protected void clear() {
        super.clear();
        branchResults.clear();
    }

    public void addResults(StateMonitor monitor, Predicate<LfBranch> isDisabled) {
        addResults(monitor, branch -> {
            var preContingencyBranchResult = preContingencyMonitorInfos.getBranchResult(branch.getId());
            double preContingencyBranchP1 = preContingencyBranchResult != null ? preContingencyBranchResult.getP1() : Double.NaN;
            double preContingencyBranchOfContingencyP1 = Double.NaN;
            if (contingency.getElements().size() == 1) {
                ContingencyElement contingencyElement = contingency.getElements().get(0);
                if (contingencyElement.getType() == ContingencyElementType.BRANCH
                        || contingencyElement.getType() == ContingencyElementType.LINE
                        || contingencyElement.getType() == ContingencyElementType.DANGLING_LINE
                        || contingencyElement.getType() == ContingencyElementType.TWO_WINDINGS_TRANSFORMER) {
                    BranchResult preContingencyBranchOfContingencyResult = preContingencyMonitorInfos.getBranchResult(contingencyElement.getId());
                    if (preContingencyBranchOfContingencyResult != null) {
                        preContingencyBranchOfContingencyP1 = preContingencyBranchOfContingencyResult.getP1();
                    }
                }
            }
            branchResults.addAll(branchResultsCreator.create(branch, preContingencyBranchP1, preContingencyBranchOfContingencyP1, createResultExtension));
        }, isDisabled);
    }

    @Override
    public void update() {
        update(LfBranch::isDisabled);
    }

    public void update(Predicate<LfBranch> isDisabled) {
        clear();
        StateMonitor stateMonitor = monitorIndex.getSpecificStateMonitors().get(contingency.getId());
        if (stateMonitor != null) {
            addResults(stateMonitor, isDisabled);
        } else {
            addResults(monitorIndex.getAllStateMonitor(), isDisabled);
        }
    }

    @Override
    public List<BranchResult> getBranchResults() {
        return branchResults;
    }
}
