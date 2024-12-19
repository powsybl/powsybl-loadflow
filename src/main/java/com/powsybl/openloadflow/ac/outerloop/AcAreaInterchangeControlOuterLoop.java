/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.lf.outerloop.AbstractAreaInterchangeControlOuterLoop;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class AcAreaInterchangeControlOuterLoop
        extends AbstractAreaInterchangeControlOuterLoop<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcOuterLoopContext>
        implements AcOuterLoop, AcActivePowerDistributionOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcAreaInterchangeControlOuterLoop.class);

    public AcAreaInterchangeControlOuterLoop(ActivePowerDistribution activePowerDistribution, double slackBusPMaxMismatch, double areaInterchangePMaxMismatch) {
        super(activePowerDistribution, new DistributedSlackOuterLoop(activePowerDistribution, slackBusPMaxMismatch), slackBusPMaxMismatch, areaInterchangePMaxMismatch, LOGGER);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public double getSlackBusActivePowerMismatch(AcOuterLoopContext context) {
        return context.getLastSolverResult().getSlackBusActivePowerMismatch();
    }

}
