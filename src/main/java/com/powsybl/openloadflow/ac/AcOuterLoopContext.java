/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverResult;
import com.powsybl.openloadflow.lf.outerloop.AbstractOuterLoopContext;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcOuterLoopContext extends AbstractOuterLoopContext<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext> {

    private AcSolverResult lastSolverResult;
    private Object outerLoopInitData;

    AcOuterLoopContext(LfNetwork network, Object dataForRunFromPreviousValues) {
        super(network);
        this.outerLoopInitData = dataForRunFromPreviousValues;
    }

    public Optional<Object> getOuterLoopInitData() {
        return Optional.ofNullable(outerLoopInitData);
    }

    public AcSolverResult getLastSolverResult() {
        return lastSolverResult;
    }

    public void setLastSolverResult(AcSolverResult lastSolverResult) {
        this.lastSolverResult = lastSolverResult;
    }

}
