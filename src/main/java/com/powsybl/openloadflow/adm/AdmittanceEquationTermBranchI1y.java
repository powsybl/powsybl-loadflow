/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

/**
 * i1y = (b1 + b12) * v1x + (g1 + g12) * v1y - b12 * v2x - g12 * v2y
 *
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AdmittanceEquationTermBranchI1y extends AbstractAdmittanceEquationTerm {

    private final double g12;

    private final double b12;

    private final double g1g12sum;

    private final double b1b12sum;

    public AdmittanceEquationTermBranchI1y(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AdmittanceVariableType> variableSet) {
        super(branch, bus1, bus2, variableSet);
        g12 = rho * zInvSquare * (r * cosA + x * sinA);
        b12 = -rho * zInvSquare * (x * cosA + r * sinA);
        g1g12sum = rho * rho * (gPi1 + r * zInvSquare);
        b1b12sum = rho * rho * (bPi1 - x * zInvSquare);
    }

    @Override
    public double der(Variable<AdmittanceVariableType> variable) {
        if (variable.equals(v1xVar)) {
            return b1b12sum;
        } else if (variable.equals(v2xVar)) {
            return -b12;
        } else if (variable.equals(v1yVar)) {
            return g1g12sum;
        } else if (variable.equals(v2yVar)) {
            return -g12;
        } else {
            throw new IllegalArgumentException("Unknown variable " + variable);
        }
    }

    @Override
    protected String getName() {
        return "adm_i1y";
    }
}
