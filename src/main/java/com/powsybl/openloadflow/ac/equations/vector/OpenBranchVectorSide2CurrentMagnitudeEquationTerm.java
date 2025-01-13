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
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.ac.equations.OpenBranchSide2CurrentMagnitudeEquationTerm.di1dv1;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
@SuppressWarnings("squid:S00107")
public class OpenBranchVectorSide2CurrentMagnitudeEquationTerm extends AbstractOpenSide2BranchVectorAcFlowEquationTerm {

    private final Variable<AcVariableType> v1Var;

    private final Variable<AcVariableType> ph1Var;

    private Variable<AcVariableType> r1Var;

    public OpenBranchVectorSide2CurrentMagnitudeEquationTerm(AcBranchVector branchVector, int branchNum, int bus1Num,
                                                             VariableSet<AcVariableType> variableSet, boolean deriveR1) {
        super(branchVector, branchNum, AcVariableType.BUS_V, bus1Num, variableSet);
        v1Var = variableSet.getVariable(bus1Num, AcVariableType.BUS_V);
        ph1Var = variableSet.getVariable(bus1Num, AcVariableType.BUS_PHI);
        if (deriveR1) {
            r1Var = variableSet.getVariable(branchNum, AcVariableType.BRANCH_RHO1);
        }
    }

    private double v1() {
        return sv.get(v1Var.getRow());
    }

    private double ph1() {
        return sv.get(ph1Var.getRow());
    }

    private double r1() {
        return r1Var != null ? sv.get(r1Var.getRow()) : branchVector.r1[num];
    }

    @Override
    public double eval() {
        return branchVector.i1[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double y = branchVector.y[num];
        double ksi = branchVector.ksi[num];
        double g1 = branchVector.g1[num];
        double b1 = branchVector.b1[num];
        double b2 = branchVector.b2[num];
        double g2 = branchVector.g2[num];
        if (variable.equals(v1Var)) {
            return di1dv1(y, FastMath.cos(ksi), FastMath.sin(ksi), g1, b1, g2, b2, v1(), ph1(), r1());
        } else if (variable.equals(ph1Var) || variable.equals(r1Var)) {
            throw new IllegalArgumentException("Derivative with respect to ph1 or r1 not implemented");
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_i_open_2";
    }
}
