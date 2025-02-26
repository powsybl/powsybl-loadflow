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
public class OpenBranchVectorSide2ReactiveFlowEquationTerm extends AbstractOpenSide2BranchVectorAcFlowEquationTerm {

    private final Variable<AcVariableType> v1Var;

    public OpenBranchVectorSide2ReactiveFlowEquationTerm(AcBranchVector branchVector, int branchNum, int bus1Num,
                                                         VariableSet<AcVariableType> variableSet) {
        super(branchVector, branchNum, AcVariableType.BUS_V, bus1Num, variableSet);
        v1Var = variableSet.getVariable(bus1Num, AcVariableType.BUS_V);
    }

    @Override
    public double eval() {
        return branchVector.q1[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return branchVector.dq1dv1[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_open_2";
    }
}
