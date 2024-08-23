/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.Vectors;
import net.jafama.FastMath;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DefaultNewtonRaphsonStoppingCriteria implements NewtonRaphsonStoppingCriteria {

    private final double convEpsPerEq;

    public DefaultNewtonRaphsonStoppingCriteria() {
        this(NewtonRaphsonStoppingCriteria.DEFAULT_CONV_EPS_PER_EQ);
    }

    public DefaultNewtonRaphsonStoppingCriteria(double convEpsPerEq) {
        this.convEpsPerEq = convEpsPerEq;
    }

    @Override
    public TestResult test(double[] fx, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        // calculate norm L2 of equations mismatch vector
        double norm = Vectors.norm2(fx);
        boolean stop = norm < FastMath.sqrt(convEpsPerEq * convEpsPerEq * fx.length);
        return new TestResult(stop, norm);
    }
}
