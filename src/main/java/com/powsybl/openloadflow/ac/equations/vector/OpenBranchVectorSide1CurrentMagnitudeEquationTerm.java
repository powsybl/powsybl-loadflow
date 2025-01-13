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

import static com.powsybl.openloadflow.ac.equations.OpenBranchSide1CurrentMagnitudeEquationTerm.di2dv2;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
@SuppressWarnings("squid:S00107")
public class OpenBranchVectorSide1CurrentMagnitudeEquationTerm extends AbstractOpenSide1BranchVectorAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    private final Variable<AcVariableType> ph2Var;

    public OpenBranchVectorSide1CurrentMagnitudeEquationTerm(AcBranchVector branchVector, int branchNum, int bus2Num,
                                                             VariableSet<AcVariableType> variableSet) {
        super(branchVector, branchNum, AcVariableType.BUS_V, bus2Num, variableSet);
        v2Var = variableSet.getVariable(bus2Num, AcVariableType.BUS_V);
        ph2Var = variableSet.getVariable(bus2Num, AcVariableType.BUS_PHI);
    }

    private double v2() {
        return sv.get(v2Var.getRow());
    }

    private double ph2() {
        return sv.get(ph2Var.getRow());
    }

    @Override
    public double eval() {
        return branchVector.i2[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double y = branchVector.y[num];
        double ksi = branchVector.ksi[num];
        double g1 = branchVector.g1[num];
        double b1 = branchVector.b1[num];
        double g2 = branchVector.g2[num];
        double b2 = branchVector.b2[num];
        if (variable.equals(v2Var)) {
            return di2dv2(y, FastMath.cos(ksi), FastMath.sin(ksi), g1, b1, g2, b2, v2(), ph2());
        } else if (variable.equals(ph2Var)) {
            throw new IllegalArgumentException("Derivative with respect to ph2 not implemented");
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_i_open_1";
    }
}
