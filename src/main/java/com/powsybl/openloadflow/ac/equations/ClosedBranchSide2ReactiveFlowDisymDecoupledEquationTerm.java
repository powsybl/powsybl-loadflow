/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchSide2ReactiveFlowDisymDecoupledEquationTerm extends AbstractClosedBranchDisymDecoupledFlowEquationTerm {

    public ClosedBranchSide2ReactiveFlowDisymDecoupledEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                   boolean deriveA1, boolean deriveR1, DisymAcSequenceType sequenceType) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, sequenceType);
    }

    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double v1 = v1();
        double r1 = r1();
        double v2 = v2();
        double theta = theta2(ksi, ph1(), a1(), ph2());
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        return dq2dph1(y, v1, r1, v2, sinTheta) * dph1
                + dq2dph2(y, v1, r1, v2, sinTheta) * dph2
                + dq2dv1(y, r1, v2, cosTheta) * dv1
                + dq2dv2(y, FastMath.cos(ksi), b2, v1, r1, v2, cosTheta) * dv2;
    }

    public static double q2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * v2 * (-b2 * R2 * v2 - y * r1 * v1 * cosTheta + y * R2 * v2 * cosKsi);
    }

    private static double dq2dv1(double y, double r1, double v2, double cosTheta) {
        return -y * r1 * R2 * v2 * cosTheta;
    }

    private static double dq2dv2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * (-2 * b2 * R2 * v2 - y * r1 * v1 * cosTheta + 2 * y * R2 * v2 * cosKsi);
    }

    private static double dq2dph1(double y, double v1, double r1, double v2, double sinTheta) {
        return y * r1 * R2 * v1 * v2 * sinTheta;
    }

    private static double dq2dph2(double y, double v1, double r1, double v2, double sinTheta) {
        return -dq2dph1(y, v1, r1, v2, sinTheta);
    }

    private static double dq2da1(double y, double v1, double r1, double v2, double sinTheta) {
        return dq2dph1(y, v1, r1, v2, sinTheta);
    }

    private static double dq2dr1(double y, double v1, double v2, double cosTheta) {
        return -y * R2 * v1 * v2 * cosTheta;
    }

    @Override
    public double eval() {
        return q2(y, FastMath.cos(ksi), b2, v1(), r1(), v2(), FastMath.cos(theta2(ksi, ph1(), a1(), ph2())));
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double theta = theta2(ksi, ph1(), a1(), ph2());
        if (variable.equals(v1Var)) {
            return dq2dv1(y, r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(v2Var)) {
            return dq2dv2(y, FastMath.cos(ksi), b2, v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(ph1Var)) {
            return dq2dph1(y, v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(ph2Var)) {
            return dq2dph2(y, v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(a1Var)) {
            return dq2da1(y, v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(r1Var)) {
            return dq2dr1(y, v1(), v2(), FastMath.cos(theta));
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_closed_2";
    }
}
