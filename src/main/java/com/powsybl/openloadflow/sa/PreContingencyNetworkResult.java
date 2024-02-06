/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfTieLineBranch;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PreContingencyNetworkResult extends AbstractNetworkResult {

    private final Map<String, BranchResult> branchResults = new HashMap<>();

    public PreContingencyNetworkResult(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension) {
        super(network, monitorIndex, createResultExtension);
    }

    @Override
    protected void clear() {
        super.clear();
        branchResults.clear();
    }

    private void addResults(StateMonitor monitor) {
        addResults(monitor, branch -> {
            List<BranchResult> branchResult = branch.createBranchResult(Double.NaN, Double.NaN, createResultExtension);
            if (branchResult.size() == 1) {
                branchResults.put(branch.getId(), branchResult.get(0));
            } else if (branch instanceof LfTieLineBranch) {
                LfTieLineBranch lfTieLineBranch = (LfTieLineBranch) branch;
                branchResults.put(lfTieLineBranch.getId(), branchResult.get(0));
                branchResults.put(lfTieLineBranch.getHalf1().getId(), branchResult.get(1));
                branchResults.put(lfTieLineBranch.getHalf2().getId(), branchResult.get(2));
            }
        });
    }

    @Override
    public void update() {
        clear();
        addResults(monitorIndex.getNoneStateMonitor());
        addResults(monitorIndex.getAllStateMonitor());
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
