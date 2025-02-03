/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop.config;

import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcOuterLoop;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class DefaultDcOuterLoopConfig extends AbstractDcOuterLoopConfig {

    @Override
    public List<DcOuterLoop> configure(LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        List<DcOuterLoop> outerLoops = new ArrayList<>(2);
        // incremental phase control
        createIncrementalPhaseControlOuterLoop(parameters).ifPresent(outerLoops::add);
        // area interchange control
        createAreaInterchangeControlOuterLoop(parameters, parametersExt).ifPresent(outerLoops::add);
        return outerLoops;
    }
}
