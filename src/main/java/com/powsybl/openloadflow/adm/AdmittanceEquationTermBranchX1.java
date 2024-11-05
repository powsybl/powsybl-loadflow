/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

/**
 * I1x = (g1 + g12)V1x - (b1 + b12)V1y - g12 * V2x + b12 * V2y
 *
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class AdmittanceEquationTermBranchX1 extends AbstractAdmittanceEquationTerm {

    private final double g12;

    private final double b12;

    private final double g1g12sum;

    private final double b1b12sum;

    public AdmittanceEquationTermBranchX1(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<VariableType> variableSet) {
        super(branch, bus1, bus2, variableSet);
        g12 = rho * zInvSquare * (r * cosA + x * sinA);
        b12 = -rho * zInvSquare * (x * cosA + r * sinA);
        g1g12sum = rho * rho * (gPi1 + r * zInvSquare);
        b1b12sum = rho * rho * (bPi1 - x * zInvSquare);
    }

    @Override
    public double der(Variable<VariableType> variable) {
        if (variable.equals(v1rVar)) {
            return g1g12sum;
        } else if (variable.equals(v2rVar)) {
            return -g12;
        } else if (variable.equals(v1iVar)) {
            return -b1b12sum;
        } else if (variable.equals(v2iVar)) {
            return b12;
        } else {
            throw new IllegalArgumentException("Unknown variable " + variable);
        }
    }

    @Override
    protected String getName() {
        return "yr1";
    }
}
