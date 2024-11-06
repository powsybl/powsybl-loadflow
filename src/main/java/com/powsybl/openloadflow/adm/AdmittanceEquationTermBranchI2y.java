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
 * i2y = -b21 * v1x - g21 * v1y + (b2 + b21) * v2x + (g2 + g21) * v2y
 *
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AdmittanceEquationTermBranchI2y extends AbstractAdmittanceEquationTerm {

    private final double g21;

    private final double b21;

    private final double g2g21sum;

    private final double b2b21sum;

    public AdmittanceEquationTermBranchI2y(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AdmittanceVariableType> variableSet) {
        super(branch, bus1, bus2, variableSet);
        double g12 = rho * zInvSquare * (r * cosA + x * sinA);
        g21 = g12;
        b21 = rho * zInvSquare * (r * sinA - x * cosA);
        g2g21sum = r * zInvSquare + gPi2;
        b2b21sum = -x * zInvSquare + bPi2;
    }

    @Override
    public double der(Variable<AdmittanceVariableType> variable) {
        if (variable.equals(v1xVar)) {
            return -b21;
        } else if (variable.equals(v2xVar)) {
            return b2b21sum;
        } else if (variable.equals(v1yVar)) {
            return -g21;
        } else if (variable.equals(v2yVar)) {
            return g2g21sum;
        } else {
            throw new IllegalArgumentException("Unknown variable " + variable);
        }
    }

    @Override
    protected String getName() {
        return "adm_yi2";
    }
}
