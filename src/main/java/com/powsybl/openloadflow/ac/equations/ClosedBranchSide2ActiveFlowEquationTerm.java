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
public class ClosedBranchSide2ActiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1, AcVectorEngine acVectorEnginee) {
        this(branch, bus1, bus2, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE, acVectorEnginee);
    }

    public ClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1, Fortescue.SequenceType sequenceType, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, sequenceType, acVectorEnginee);
    }

    @Override
    public VectorEngine.VecToVal getVecToVal(Variable<AcVariableType> v) {
        if (v == v1Var) {
            return ClosedBranchSide2ActiveFlowEquationTerm::vec2dp2dv1;
        }
        if (v == v2Var) {
            return ClosedBranchSide2ActiveFlowEquationTerm::vec2dp2dv2;
        }
        if (v == ph1Var) {
            return ClosedBranchSide2ActiveFlowEquationTerm::vec2dp2dph1;
        }
        if (v == ph2Var) {
            return ClosedBranchSide2ActiveFlowEquationTerm::vec2dp2dph2;
        } else if (v == null) {
            return ClosedBranchSide2ActiveFlowEquationTerm::vec2p2;
        }
        return null;
    }

    public static double calculateSensi(double y, double ksi, double g2,
                                        double v1, double ph1, double r1, double a1, double v2, double ph2,
                                        double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double theta = theta2(ksi, ph1, a1, ph2);
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        return dp2dph1(y, v1, r1, v2, cosTheta) * dph1
                + dp2dph2(y, v1, r1, v2, cosTheta) * dph2
                + dp2dv1(y, r1, v2, sinTheta) * dv1
                + dp2dv2(y, FastMath.sin(ksi), g2, v1, r1, v2, sinTheta) * dv2
                + dp2da1(y, v1, r1, v2, cosTheta) * da1
                + dp2dr1(y, v1, v2, sinTheta) * dr1;
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        return calculateSensi(y(), ksi(), g2(), v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    public static double vec2p2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                double sinTheta1, double cosTheta1, double b1, double b2, double g1, double g2, double y,
                                double g12, double b12, double a1, double r1) {
        return p2(y, sinKsi, g2, v1, r1, v2, sinTheta2);
    }

    public static double p2(double y, double sinKsi, double g2, double v1, double r1, double v2, double sinTheta) {
        return R2 * v2 * (g2 * R2 * v2 - y * r1 * v1 * sinTheta + y * R2 * v2 * sinKsi);
    }

    public static double vec2dp2dv1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dp2dv1(y, r1, v2, sinTheta2);
    }

    public static double dp2dv1(double y, double r1, double v2, double sinTheta) {
        return -y * r1 * R2 * v2 * sinTheta;
    }

    public static double vec2dp2dv2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2,
                                    double sinTheta1, double cosTheta1,
                                    double b1, double b2, double g1, double g2, double y,
                                    double g12, double b12, double a1, double r1) {
        return dp2dv2(y, sinKsi, g2, v1, r1, v2, sinTheta2);
    }

    public static double dp2dv2(double y, double sinKsi, double g2, double v1, double r1, double v2, double sinTheta) {
        return R2 * (2 * g2 * R2 * v2 - y * r1 * v1 * sinTheta + 2 * y * R2 * v2 * sinKsi);
    }

    public static double vec2dp2dph1(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2, double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return dp2dph1(y, v1, r1, v2, cosTheta2);
    }

    public static double dp2dph1(double y, double v1, double r1, double v2, double cosTheta) {
        return -y * r1 * R2 * v1 * v2 * cosTheta;
    }

    public static double vec2dp2dph2(double v1, double v2, double sinKsi, double cosKsi, double sinTheta2, double cosTheta2, double sinTheta1, double cosTheta1,
                                     double b1, double b2, double g1, double g2, double y,
                                     double g12, double b12, double a1, double r1) {
        return dp2dph2(y, v1, r1, v2, cosTheta2);
    }

    public static double dp2dph2(double y, double v1, double r1, double v2, double cosTheta) {
        return -dp2dph1(y, v1, r1, v2, cosTheta);
    }

    public static double dp2da1(double y, double v1, double r1, double v2, double cosTheta) {
        return dp2dph1(y, v1, r1, v2, cosTheta);
    }

    public static double dp2dr1(double y, double v1, double v2, double sinTheta) {
        return -y * R2 * v1 * v2 * sinTheta;
    }

    @Override
    public double eval() {
        return p2(y(), FastMath.sin(ksi()), g2(), v1(), r1(), v2(), FastMath.sin(theta2(ksi(), ph1(), a1(), ph2())));
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        double theta = theta2(ksi(), ph1(), a1(), ph2());
        if (variable.equals(v1Var)) {
            return dp2dv1(y(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(v2Var)) {
            return dp2dv2(y(), FastMath.sin(ksi()), g2(), v1(), r1(), v2(), FastMath.sin(theta));
        } else if (variable.equals(ph1Var)) {
            return dp2dph1(y(), v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(ph2Var)) {
            return dp2dph2(y(), v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(a1Var)) {
            return dp2da1(y(), v1(), r1(), v2(), FastMath.cos(theta));
        } else if (variable.equals(r1Var)) {
            return dp2dr1(y(), v1(), v2(), FastMath.sin(theta));
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_closed_2";
    }
}
