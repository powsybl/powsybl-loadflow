/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenBranchVectorSide1ActiveFlowEquationTerm extends AbstractOpenSide1BranchVectorAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    public OpenBranchVectorSide1ActiveFlowEquationTerm(AcBranchVector branchVector, int branchNum, int bus2Num,
                                                       VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum, AcVariableType.BUS_V, bus2Num, variableSet, deriveA1, deriveR1);
        v2Var = variableSet.getVariable(bus2Num, AcVariableType.BUS_V);
    }

    @Override
    public double eval() {
        return branchVector.p2[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return branchVector.dp2dv2[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_open_1";
    }
}
