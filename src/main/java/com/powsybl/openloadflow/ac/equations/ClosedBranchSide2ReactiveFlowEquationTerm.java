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
public class ClosedBranchSide2ReactiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE, acVectorEnginee);
    }

    public ClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1, Fortescue.SequenceType sequenceType, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, sequenceType, acVectorEnginee);
    }

    @Override
    public VectorEngine.VecToVal getVecToVal(Variable<AcVariableType> v) {
        if (v == v1Var) {
            return ClosedBranchSide2ReactiveFlowEquationTerm::vec2dq2dv1;
        } else if (v == v2Var) {
            return ClosedBranchSide2ReactiveFlowEquationTerm::vec2dq2dv2;
        } else if (v == ph1Var) {
            return ClosedBranchSide2ReactiveFlowEquationTerm::vec2dq2dph1;
        } else if (v == ph2Var) {
            return ClosedBranchSide2ReactiveFlowEquationTerm::vec2dq2dph2;
        } else if (v == null) {
            return ClosedBranchSide2ReactiveFlowEquationTerm::vec2q2;
        }
        return null;
        // TODO return for eval
        // acVectorEnginee.vecToP2[element.getNum()] = ClosedBranchSide2ActiveFlowEquationTerm::vec2p2;}
    }

    public static double calculateSensi(double y, double ksi, double b2,
                                        double v1, double ph1, double r1, double a1, double v2, double ph2,
                                        double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double theta = theta2(ksi, ph1, a1, ph2);
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        return dq2dph1(y, v1, r1, v2, sinTheta) * dph1
                + dq2dph2(y, v1, r1, v2, sinTheta) * dph2
                + dq2dv1(y, r1, v2, cosTheta) * dv1
                + dq2dv2(y, FastMath.cos(ksi), b2, v1, r1, v2, cosTheta) * dv2
                + dq2da1(y, v1, r1, v2, sinTheta) * da1
                + dq2dr1(y, v1, v2, cosTheta) * dr1;
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        return calculateSensi(y(), ksi(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    public static double vec2q2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                     double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return q2(y, cosKsi, b2, v1, r1, v2, cosTheta2);
    }

    public static double q2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * v2 * (-b2 * R2 * v2 - y * r1 * v1 * cosTheta + y * R2 * v2 * cosKsi);
    }

    public static double vec2dq2dv1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq2dv1(y, r1, v2, cosTheta2);
    }

    public static double dq2dv1(double y, double r1, double v2, double cosTheta) {
        return -y * r1 * R2 * v2 * cosTheta;
    }

    public static double vec2dq2dv2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq2dv2(y, cosKsi, b2, v1, r1, v2, cosTheta2);
    }

    public static double dq2dv2(double y, double cosKsi, double b2, double v1, double r1, double v2, double cosTheta) {
        return R2 * (-2 * b2 * R2 * v2 - y * r1 * v1 * cosTheta + 2 * y * R2 * v2 * cosKsi);
    }

    public static double vec2dq2dph1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dq2dph1(y, v1, r1, v2, sinTheta2);
    }

    public static double dq2dph1(double y, double v1, double r1, double v2, double sinTheta) {
        return y * r1 * R2 * v1 * v2 * sinTheta;
    }

    public static double vec2dq2dph2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                     double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return dq2dph2(y, v1, r1, v2, sinTheta2);
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
        return q2(y(), FastMath.cos(ksi()), b2(), v1(), r1(), v2(), FastMath.cos(theta2(ksi(), ph1(), a1(), ph2())));
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double theta = theta2(ksi(), ph1(), a1(), ph2());
        if (variable.equals(v1Var)) {
            return dq2dv1(y(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(v2Var)) {
            return dq2dv2(y(), FastMath.cos(ksi()), b2(), v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(ph1Var)) {
            return dq2dph1(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(ph2Var)) {
            return dq2dph2(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(a1Var)) {
            return dq2da1(y(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(r1Var)) {
            return dq2dr1(y(), v1(), v2(), FastMath.cos(theta));
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_closed_2";
    }
}
