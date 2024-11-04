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
 * I2x = -g21 * V1x + b21 * V1y + (g2 + g21)V2x - (b2 + b21)V2y
 *
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class AdmittanceEquationTermX2 extends AbstractAdmittanceEquationTerm {

    private final double g21;

    private final double b21;

    private final double g2g21sum;

    private final double b2b21sum;

    public AdmittanceEquationTermX2(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<VariableType> variableSet) {
        super(branch, bus1, bus2, variableSet);
        double g12 = rho * zInvSquare * (r * cosA + x * sinA);
        g21 = g12;
        b21 = rho * zInvSquare * (r * sinA - x * cosA);
        g2g21sum = r * zInvSquare + gPi2;
        b2b21sum = -x * zInvSquare + bPi2;
    }

    @Override
    public double getCoefficient(Variable<VariableType> variable) {
        if (variable.equals(v1rVar)) {
            return -g21;
        } else if (variable.equals(v2rVar)) {
            return g2g21sum;
        } else if (variable.equals(v1iVar)) {
            return b21;
        } else if (variable.equals(v2iVar)) {
            return -b2b21sum;
        } else {
            throw new IllegalArgumentException("Unknown variable " + variable);
        }
    }

    @Override
    protected String getName() {
        return "yr2";
    }
}
