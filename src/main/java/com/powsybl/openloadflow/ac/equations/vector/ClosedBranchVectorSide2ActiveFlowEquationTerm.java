/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide2ActiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.util.Fortescue;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchVectorSide2ActiveFlowEquationTerm extends AbstractClosedBranchVectorAcFlowEquationTerm {

    public ClosedBranchVectorSide2ActiveFlowEquationTerm(AcBranchVector branchVector, int branchNum, int bus1Num, int bus2Num,
                                                         VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum, bus1Num, bus2Num, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE);
    }

    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double y = branchVector.y[num];
        double ksi = branchVector.ksi[num];
        double g2 = branchVector.g2[num];
        return ClosedBranchSide2ActiveFlowEquationTerm.calculateSensi(y, ksi, g2, v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    @Override
    public double eval() {
        return branchVector.p2[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return branchVector.dp2dv1[num];
        } else if (variable.equals(v2Var)) {
            return branchVector.dp2dv2[num];
        } else if (variable.equals(ph1Var)) {
            return branchVector.dp2dph1[num];
        } else if (variable.equals(ph2Var)) {
            return branchVector.dp2dph2[num];
        } else if (variable.equals(a1Var)) {
            return branchVector.dp2da1[num];
        } else if (variable.equals(r1Var)) {
            return branchVector.dp2dr1[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_closed_2";
    }
}