/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class OpenBranchSide1ReactiveFlowEquationTerm extends AbstractOpenSide1BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    public OpenBranchSide1ReactiveFlowEquationTerm(AcBranchVector branchVector, int branchNum, int bus2Num,
                                                   VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum, AcVariableType.BUS_V, bus2Num, variableSet, deriveA1, deriveR1);
        v2Var = variableSet.getVariable(bus2Num, AcVariableType.BUS_V);
    }

    public static double q2(double y, double cosKsi, double sinKsi, double g1, double b1, double b2, double v2) {
        double shunt = shunt(y, cosKsi, sinKsi, g1, b1);
        return -R2 * R2 * v2 * v2 * (b2 + y * y * b1 / shunt - (b1 * b1 + g1 * g1) * y * cosKsi / shunt);
    }

    public static double dq2dv2(double y, double cosKsi, double sinKsi, double g1, double b1, double b2, double v2) {
        double shunt = shunt(y, cosKsi, sinKsi, g1, b1);
        return -2 * v2 * R2 * R2 * (b2 + y * y * b1 / shunt - (b1 * b1 + g1 * g1) * y * cosKsi / shunt);
    }

    @Override
    public double eval() {
        return branchVector.q2[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return branchVector.dq2dv2[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_open_1";
    }
}
