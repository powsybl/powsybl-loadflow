/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.OuterLoop;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopConfig;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class DefaultOuterLoopConfig implements OuterLoopConfig {

    @Override
    public List<OuterLoop> configure(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        List<OuterLoop> outerLoops = new ArrayList<>(5);
        if (parameters.isDistributedSlack()) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(parameters.getBalanceType(), parametersExt.isLoadPowerFactorConstant());
            outerLoops.add(new DistributedSlackOuterLoop(activePowerDistribution, parametersExt.isThrowsExceptionInCaseOfSlackDistributionFailure(), parametersExt.getSlackBusPMaxMismatch()));
        }
        if (parameters.isPhaseShifterRegulationOn()) {
            outerLoops.add(new PhaseControlOuterLoop());
        }
        if (!parameters.isNoGeneratorReactiveLimits()) {
            outerLoops.add(new ReactiveLimitsOuterLoop());
        }
        if (parameters.isTransformerVoltageControlOn()) {
            outerLoops.add(new TransformerVoltageControlOuterLoop());
        }
        if (parameters.isShuntCompensatorVoltageControlOn()) {
            outerLoops.add(new ShuntVoltageControlOuterLoop());
        }
        return outerLoops;
    }
}
