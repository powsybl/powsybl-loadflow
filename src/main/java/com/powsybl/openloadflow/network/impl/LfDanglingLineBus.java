/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.DanglingLine;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfDanglingLineBus extends AbstractLfBus {

    private final DanglingLine danglingLine;

    private final double nominalV;

    public LfDanglingLineBus(LfNetwork network, DanglingLine danglingLine, boolean reactiveLimits, LfNetworkLoadingReport report) {
        super(network, Networks.getPropertyV(danglingLine), Networks.getPropertyAngle(danglingLine));
        this.danglingLine = Objects.requireNonNull(danglingLine);
        nominalV = danglingLine.getTerminal().getVoltageLevel().getNominalV();
        loadTargetP += danglingLine.getP0();
        loadTargetQ += danglingLine.getQ0();
        DanglingLine.Generation generation = danglingLine.getGeneration();
        if (generation != null) {
            addGenerator(new LfDanglingLineGenerator(danglingLine, network, getId(), reactiveLimits, report));
        }
    }

    public static String getId(DanglingLine danglingLine) {
        return danglingLine.getId() + "_BUS";
    }

    @Override
    public List<String> getOriginalIds() {
        return List.of(danglingLine.getId());
    }

    @Override
    public String getId() {
        return getId(danglingLine);
    }

    @Override
    public String getVoltageLevelId() {
        return danglingLine.getTerminal().getVoltageLevel().getId();
    }

    @Override
    public boolean isFictitious() {
        return true;
    }

    @Override
    public double getNominalV() {
        return nominalV;
    }

    @Override
    public void updateState(boolean reactiveLimits, boolean writeSlackBus, boolean distributedOnConformLoad, boolean loadPowerFactorConstant) {
        Networks.setPropertyV(danglingLine, v);
        Networks.setPropertyAngle(danglingLine, angle);

        super.updateState(reactiveLimits, writeSlackBus, false, false);
    }
}
