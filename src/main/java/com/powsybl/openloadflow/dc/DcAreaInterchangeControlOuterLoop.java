/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.lf.outerloop.AbstractAreaInterchangeControlOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class DcAreaInterchangeControlOuterLoop extends AbstractAreaInterchangeControlOuterLoop<DcVariableType, DcEquationType, DcLoadFlowParameters, DcLoadFlowContext, DcOuterLoopContext> implements DcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(DcAreaInterchangeControlOuterLoop.class);

    public DcAreaInterchangeControlOuterLoop(ActivePowerDistribution activePowerDistribution, double slackBusPMaxMismatch, double areaInterchangePMaxMismatch) {
        super(activePowerDistribution, new DcNoAreaOuterLoop(), slackBusPMaxMismatch, areaInterchangePMaxMismatch, LOGGER);
    }

    @Override
    public String getName() {
        return "AreaInterchangeControl";
    }

    @Override
    public double getSlackBusActivePowerMismatch(DcOuterLoopContext context) {
        List<LfBus> buses = context.getNetwork().getBuses();
        return DcLoadFlowEngine.getActivePowerMismatch(buses);
    }

    /**
     * If the network has no area, the area interchange control is replaced by slack distribution.
     * In DC mode, the slack distribution is handled directly by the load flow engine, without any outer loop.
     * This class will be used as fallback outerloop in case the network has no area, and has no need to do anything.
     */
    private static class DcNoAreaOuterLoop implements DcOuterLoop {
        @Override
        public String getName() {
            return "DcNoAreaOuterLoop";
        }

        @Override
        public OuterLoopResult check(DcOuterLoopContext context, ReportNode reportNode) {
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }
    }
}
