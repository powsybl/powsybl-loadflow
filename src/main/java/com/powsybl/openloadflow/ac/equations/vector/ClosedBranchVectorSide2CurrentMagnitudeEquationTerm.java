/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ClosedBranchSide2CurrentMagnitudeEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.util.Fortescue;

import java.util.Objects;

import static com.powsybl.openloadflow.ac.equations.ClosedBranchSide2CurrentMagnitudeEquationTerm.*;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchVectorSide2CurrentMagnitudeEquationTerm extends AbstractClosedBranchVectorAcFlowEquationTerm {

    public ClosedBranchVectorSide2CurrentMagnitudeEquationTerm(AcBranchVector branchVector, int branchNum, int bus1Num, int bus2Num,
                                                               VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum, bus1Num, bus2Num, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE);
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double y = branchVector.y[num];
        double ksi = branchVector.ksi[num];
        double g2 = branchVector.g2[num];
        double b2 = branchVector.b2[num];
        return ClosedBranchSide2CurrentMagnitudeEquationTerm.calculateSensi(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
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
        double g2 = branchVector.g2[num];
        double b2 = branchVector.b2[num];
        if (variable.equals(v1Var)) {
            return di2dv1(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(v2Var)) {
            return di2dv2(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph1Var)) {
            return di2dph1(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph2Var)) {
            return di2dph2(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(a1Var)) {
            return di2da1(y, ksi, g2, b2, v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(r1Var)) {
            throw new IllegalArgumentException("Derivative with respect to r1 not implemented");
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_i_closed_2";
    }
}
