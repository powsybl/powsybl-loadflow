/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa.monitor;

import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfLegBranch;
import com.powsybl.openloadflow.network.impl.LfStarBus;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResult;
import com.powsybl.security.results.ThreeWindingsTransformerResult;

import java.util.*;
import java.util.function.Consumer;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractMonitorInfos {

    protected final LfNetwork network;

    protected final StateMonitorIndex monitorIndex;

    protected final boolean createResultExtension;

    protected final List<BusResult> busResults = new ArrayList<>();

    protected final List<ThreeWindingsTransformerResult> threeWindingsTransformerResults = new ArrayList<>();

    protected AbstractMonitorInfos(LfNetwork network, StateMonitorIndex monitorIndex, boolean createResultExtension) {
        this.network = Objects.requireNonNull(network);
        this.monitorIndex = Objects.requireNonNull(monitorIndex);
        this.createResultExtension = createResultExtension;
    }

    protected void addMonitorInfo(StateMonitor monitor, Consumer<LfBranch> branchConsumer) {
        Objects.requireNonNull(monitor);
        if (!monitor.getBranchIds().isEmpty()) {
            network.getBranches().stream()
                    .filter(lfBranch -> monitor.getBranchIds().contains(lfBranch.getId()))
                    .filter(lfBranch -> !lfBranch.isDisabled())
                    .forEach(branchConsumer);
        }

        if (!monitor.getVoltageLevelIds().isEmpty()) {
            network.getBuses().stream()
                    .filter(lfBus -> monitor.getVoltageLevelIds().contains(lfBus.getVoltageLevelId()))
                    .filter(lfBus -> !lfBus.isDisabled())
                    .forEach(lfBus -> busResults.add(lfBus.createBusResult()));
        }

        if (!monitor.getThreeWindingsTransformerIds().isEmpty()) {
            monitor.getThreeWindingsTransformerIds().stream()
                    .filter(id -> network.getBusById(LfStarBus.getId(id)) != null && !network.getBusById(LfStarBus.getId(id)).isDisabled())
                    .forEach(id -> threeWindingsTransformerResults.add(createThreeWindingsTransformerResult(id, network)));
        }
    }

    protected void clear() {
        busResults.clear();
        threeWindingsTransformerResults.clear();
    }

    private static ThreeWindingsTransformerResult createThreeWindingsTransformerResult(String threeWindingsTransformerId, LfNetwork network) {
        LfBranch leg1 = network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 1));
        LfBranch leg2 = network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 2));
        LfBranch leg3 = network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 3));
        double i1Base = PerUnit.ib(leg1.getBus1().getNominalV());
        double i2Base = PerUnit.ib(leg2.getBus1().getNominalV());
        double i3Base = PerUnit.ib(leg3.getBus1().getNominalV());
        return new ThreeWindingsTransformerResult(threeWindingsTransformerId,
                                                  leg1.getP1().eval() * PerUnit.SB, leg1.getQ1().eval() * PerUnit.SB, leg1.getI1().eval() * i1Base,
                                                  leg2.getP1().eval() * PerUnit.SB, leg2.getQ1().eval() * PerUnit.SB, leg2.getI1().eval() * i2Base,
                                                  leg3.getP1().eval() * PerUnit.SB, leg3.getQ1().eval() * PerUnit.SB, leg3.getI1().eval() * i3Base);
    }

    public List<BusResult> getBusResults() {
        return busResults;
    }

    public List<ThreeWindingsTransformerResult> getThreeWindingsTransformerResults() {
        return threeWindingsTransformerResults;
    }

    public abstract List<BranchResult> getBranchResults();
}
