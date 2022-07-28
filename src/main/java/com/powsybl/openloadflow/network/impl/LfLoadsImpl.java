/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.extensions.LoadDetail;
import com.powsybl.openloadflow.network.LfLoads;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class LfLoadsImpl implements LfLoads {

    private final List<Load> loads = new ArrayList<>();

    private double[] participationFactors;

    private double absVariableLoadTargetP = 0;

    private boolean distributedOnConformLoad;

    private boolean isInitialized;

    @Override
    public List<String> getOriginalIds() {
        return loads.stream().map(Identifiable::getId).collect(Collectors.toList());
    }

    void add(Load load, boolean distributedOnConformLoad) {
        loads.add(load);
        this.distributedOnConformLoad = distributedOnConformLoad; // TODO: put in constructor instead
    }

    @Override
    public double getAbsVariableLoadTargetP() {
        init();
        return absVariableLoadTargetP;
    }

    @Override
    public void setAbsVariableLoadTargetP(double absVariableLoadTargetP) {
        this.absVariableLoadTargetP = absVariableLoadTargetP;
    }

    private void init() {
        if (isInitialized) {
            return;
        }

        participationFactors = new double[loads.size()];
        for (int i = 0; i < loads.size(); i++) {
            Load load = loads.get(i);
            double value;
            if (distributedOnConformLoad) {
                value = load.getExtension(LoadDetail.class) == null ? 0. : Math.abs(load.getExtension(LoadDetail.class).getVariableActivePower());
            } else {
                value = Math.abs(load.getP0());
            }
            absVariableLoadTargetP += value;
            participationFactors[i] = value;
        }

        if (absVariableLoadTargetP != 0) {
            for (int i = 0; i < participationFactors.length; i++) {
                participationFactors[i] /= absVariableLoadTargetP;
            }
        }

        isInitialized = true;
    }

    @Override
    public double getLoadCount() {
        return loads.size();
    }

    void updateState(double diffLoadTargetP, boolean loadPowerFactorConstant) {
        init();
        for (int i = 0; i < loads.size(); i++) {
            Load load = loads.get(i);
            double updatedP0 = (load.getP0() / PerUnit.SB + diffLoadTargetP * participationFactors[i]) * PerUnit.SB;
            double updatedQ0 = loadPowerFactorConstant ? getPowerFactor(load) * updatedP0 : load.getQ0();
            load.getTerminal().setP(updatedP0);
            load.getTerminal().setQ(updatedQ0);
        }
    }

    @Override
    public double getLoadTargetQ(double diffLoadTargetP) {
        init();
        double newLoadTargetQ = 0;
        for (int i = 0; i < loads.size(); i++) {
            Load load = loads.get(i);
            double updatedP0 = load.getP0() / PerUnit.SB + diffLoadTargetP * participationFactors[i];
            newLoadTargetQ += getPowerFactor(load) * updatedP0;
        }
        return newLoadTargetQ;
    }

    private static double getPowerFactor(Load load) {
        return load.getP0() != 0 ? load.getQ0() / load.getP0() : 1;
    }

}
