/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop.config;

import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.*;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DefaultAcOuterLoopConfig extends AbstractAcOuterLoopConfig {

    @Override
    public List<AcOuterLoop> configure(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        List<AcOuterLoop> outerLoops = new ArrayList<>(5);
        // primary frequency control
        createDistributedSlackOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // area interchange control
        createAreaInterchangeControlOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // secondary voltage control
        createSecondaryVoltageControlOuterLoop(parametersExt).ifPresent(outerLoops::add);
        // primary voltage control
        createMonitoringVoltageOuterLoop(parametersExt).ifPresent(outerLoops::add);
        createReactiveLimitsOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // phase shifter control
        createPhaseControlOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // transformer voltage control
        createTransformerVoltageControlOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // transformer reactive power control
        createTransformerReactivePowerControlOuterLoop(parametersExt).ifPresent(outerLoops::add);
        // shunt compensator voltage control
        createShuntVoltageControlOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        // automation system
        createAutomationSystemOuterLoop(parametersExt).ifPresent(outerLoops::add);
        return filterInconsistentOuterLoops(outerLoops);
    }
}
