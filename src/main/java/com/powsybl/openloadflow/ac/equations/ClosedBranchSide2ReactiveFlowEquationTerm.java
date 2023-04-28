/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchSide2ReactiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2ReactiveFlowEquationTerm(AcBranchVector branchVector, int branchNum, int bus1Num, int bus2Num,
                                                     VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum, bus1Num, bus2Num, variableSet, deriveA1, deriveR1);
    }

    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double y = branchVector.y[num];
        double ksi = branchVector.ksi[num];
        double b2 = branchVector.b2[num];
        double v1 = v1();
        double r1 = r1();
        double v2 = v2();
        double theta = theta2(ksi, ph1(), a1(), ph2());
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        return dq2dph1(y, v1, r1, v2, sinTheta) * dph1
                + dq2dph2(y, v1, r1, v2, sinTheta) * dph2
                + dq2dv1(y, r1, v2, cosTheta) * dv1
                + dq2dv2(y, FastMath.cos(ksi), b2, v1, r1, v2, cosTheta) * dv2
                + dq2da1(y, v1, r1, v2, sinTheta) * da1
                + dq2dr1(y, v1, v2, cosTheta) * dr1;
    }

    public static double q2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * v2 * (-b2 * R2 * v2 - y * r1 * v1 * cosTheta + y * R2 * v2 * cosKsi);
    }

    public static double dq2dv1(double y, double r1, double v2, double cosTheta) {
        return -y * r1 * R2 * v2 * cosTheta;
    }

    public static double dq2dv2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * (-2 * b2 * R2 * v2 - y * r1 * v1 * cosTheta + 2 * y * R2 * v2 * cosKsi);
    }

    public static double dq2dph1(double y, double v1, double r1, double v2, double sinTheta) {
        return y * r1 * R2 * v1 * v2 * sinTheta;
    }

    public static double dq2dph2(double y, double v1, double r1, double v2, double sinTheta) {
        return -dq2dph1(y, v1, r1, v2, sinTheta);
    }

    public static double dq2da1(double y, double v1, double r1, double v2, double sinTheta) {
        return dq2dph1(y, v1, r1, v2, sinTheta);
    }

    public static double dq2dr1(double y, double v1, double v2, double cosTheta) {
        return -y * R2 * v1 * v2 * cosTheta;
    }

    @Override
    public double eval() {
        return branchVector.q2[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return branchVector.dq2dv1[num];
        } else if (variable.equals(v2Var)) {
            return branchVector.dq2dv2[num];
        } else if (variable.equals(ph1Var)) {
            return branchVector.dq2dph1[num];
        } else if (variable.equals(ph2Var)) {
            return branchVector.dq2dph2[num];
        } else if (variable.equals(a1Var)) {
            return branchVector.dq2da1[num];
        } else if (variable.equals(r1Var)) {
            return branchVector.dq2dr1[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_closed_2";
    }
}
