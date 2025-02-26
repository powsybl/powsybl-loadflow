/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.equations.VectorEngine;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.Fortescue;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchSide1ReactiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE, acVectorEnginee);
    }

    public ClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1, Fortescue.SequenceType sequenceType, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, sequenceType, acVectorEnginee);
    }

    @Override
    public VectorEngine.VecToVal getVecToVal(Variable<AcVariableType> v) {
        if (v == v1Var) {
            return ClosedBranchSide1ReactiveFlowEquationTerm::vec2dq1dv1;
        } else if (v == v2Var) {
            return ClosedBranchSide1ReactiveFlowEquationTerm::vec2dq1dv2;
        } else if (v == ph1Var) {
            return ClosedBranchSide1ReactiveFlowEquationTerm::vec2dq1dph1;
        } else if (v == ph2Var) {
            return ClosedBranchSide1ReactiveFlowEquationTerm::vec2dq1dph2;
        } else if (v == null) {
            return ClosedBranchSide1ReactiveFlowEquationTerm::vec2q1;
        }
        return null;
    }

    public static double calculateSensi(double y, double ksi, double b1,
                                        double v1, double ph1, double r1, double a1, double v2, double ph2,
                                        double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double theta = theta1(ksi, ph1, a1, ph2);
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        double cosKsi = FastMath.cos(ksi);
        return dq1dph1(y, v1, r1, v2, sinTheta) * dph1
                + dq1dph2(y, v1, r1, v2, sinTheta) * dph2
                + dq1dv1(y, cosKsi, b1, v1, r1, v2, cosTheta) * dv1
                + dq1dv2(y, v1, r1, cosTheta) * dv2
                + dq1da1(y, v1, r1, v2, sinTheta) * da1
                + dq1dr1(y, cosKsi, b1, v1, r1, v2, cosTheta) * dr1;
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        return calculateSensi(y(), ksi(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    public static double vec2q1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                     double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return q1(y, cosKsi, b1, v1, r1, v2, cosTheta1);
    }

    public static double q1(double y, double cosKsi, double b1, double v1, double r1, double v2, double cosTheta) {
        return r1 * v1 * (-b1 * r1 * v1 + y * r1 * v1 * cosKsi
                - y * R2 * v2 * cosTheta);
    }

    public static double vec2dq1dv1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq1dv1(y, cosKsi, b1, v1, r1, v2, cosTheta1);
    }

    public static double dq1dv1(double y, double cosKsi, double b1, double v1, double r1, double v2, double cosTheta) {
        return r1 * (-2 * b1 * r1 * v1 + 2 * y * r1 * v1 * cosKsi
                - y * R2 * v2 * cosTheta);
    }

    public static double vec2dq1dv2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq1dv2(y, v1, r1, cosTheta1);
    }

    public static double dq1dv2(double y, double v1, double r1, double cosTheta) {
        return -y * r1 * R2 * v1 * cosTheta;
    }

    public static double vec2dq1dph1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq1dph1(y, v1, r1, v2, sinTheta1);
    }

    public static double dq1dph1(double y, double v1, double r1, double v2, double sinTheta) {
        return -y * r1 * R2 * v1 * v2 * sinTheta;
    }

    public static double vec2dq1dph2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                     double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return dq1dph2(y, v1, r1, v2, sinTheta1);
    }

    public static double dq1dph2(double y, double v1, double r1, double v2, double sinTheta) {
        return -dq1dph1(y, v1, r1, v2, sinTheta);
    }

    public static double dq1da1(double y, double v1, double r1, double v2, double sinTheta) {
        return dq1dph1(y, v1, r1, v2, sinTheta);
    }

    public static double dq1dr1(double y, double cosKsi, double b1, double v1, double r1, double v2, double cosTheta) {
        return v1 * (2 * r1 * v1 * (-b1 + y * cosKsi) - y * R2 * v2 * cosTheta);
    }

    @Override
    public double eval() {
        return q1(y(), FastMath.cos(ksi()), b1(), v1(), r1(), v2(), FastMath.cos(theta1(ksi(), ph1(), a1(), ph2())));
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double theta = theta1(ksi(), ph1(), a1(), ph2());
        if (variable.equals(v1Var)) {
            return dq1dv1(y(), FastMath.cos(ksi()), b1(), v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(v2Var)) {
            return dq1dv2(y(), v1(), r1(), FastMath.cos(theta));
        } else if (variable.equals(ph1Var)) {
            return dq1dph1(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(ph2Var)) {
            return dq1dph2(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(a1Var)) {
            return dq1da1(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(r1Var)) {
            return dq1dr1(y(), FastMath.cos(ksi()), b1(), v1(), r1(), v2(), FastMath.cos(theta));
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_closed_1";
    }
}
