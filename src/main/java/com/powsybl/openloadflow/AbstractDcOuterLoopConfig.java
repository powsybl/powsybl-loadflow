/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.dc.DcAreaInterchangeControlOuterLoop;
import com.powsybl.openloadflow.dc.DcIncrementalPhaseControlOuterLoop;
import com.powsybl.openloadflow.dc.DcOuterLoop;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;

import java.util.Optional;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
abstract class AbstractDcOuterLoopConfig implements DcOuterLoopConfig {

    protected AbstractDcOuterLoopConfig() {
    }

    protected static Optional<DcOuterLoop> createAreaInterchangeControlOuterLoop(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        if (parametersExt.isAreaInterchangeControl()) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(parameters.getBalanceType(), parametersExt.isLoadPowerFactorConstant(), parametersExt.isUseActiveLimits());
            return Optional.of(new DcAreaInterchangeControlOuterLoop(activePowerDistribution, parametersExt.getSlackBusPMaxMismatch(), parametersExt.getAreaInterchangePMaxMismatch()));
        }
        return Optional.empty();
    }

    protected static Optional<DcOuterLoop> createIncrementalPhaseControlOuterLoop(LoadFlowParameters parameters) {
        if (parameters.isPhaseShifterRegulationOn()) {
            return Optional.of(new DcIncrementalPhaseControlOuterLoop());
        }
        return Optional.empty();
    }

}
