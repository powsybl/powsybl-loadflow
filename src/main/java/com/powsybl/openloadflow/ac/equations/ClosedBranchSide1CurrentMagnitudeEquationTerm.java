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
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.Fortescue;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.A2;
import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchSide1CurrentMagnitudeEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                         boolean deriveA1, boolean deriveR1, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE, acVectorEnginee);
    }

    public static double calculateSensi(double y, double ksi, double g1, double b1,
                                        double v1, double ph1, double r1, double a1, double v2, double ph2,
                                        double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        if (dr1 != 0) {
            throw new IllegalArgumentException("Derivative with respect to r1 not implemented");
        }
        return di1dph1(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2) * dph1
                + di1dph2(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2) * dph2
                + di1dv1(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2) * dv1
                + di1dv2(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2) * dv2
                + di1da1(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2) * da1;
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        return calculateSensi(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    private static double theta(double ksi, double a1, double ph2) {
        return ksi - a1 + A2 + ph2;
    }

    private static double interReI1(double y, double ksi, double g1, double b1, double ph1) {
        return g1 * FastMath.cos(ph1) - b1 * FastMath.sin(ph1) + y * FastMath.sin(ph1 + ksi);
    }

    private static double interImI1(double y, double ksi, double g1, double b1, double ph1) {
        return g1 * FastMath.sin(ph1) + b1 * FastMath.cos(ph1) - y * FastMath.cos(ph1 + ksi);
    }

    private static double reI1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double v2, double theta) {
        return r1 * (r1 * v1 * interReI1(y, ksi, g1, b1, ph1) - y * R2 * v2 * FastMath.sin(theta));
    }

    private static double imI1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double v2, double theta) {
        return r1 * (r1 * v1 * interImI1(y, ksi, g1, b1, ph1) + y * R2 * v2 * FastMath.cos(theta));
    }

    private static double i1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double v2, double theta) {
        return FastMath.hypot(reI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta), imI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta));
    }

    public static double i1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, a1, ph2);
        return i1(y, ksi, g1, b1, v1, ph1, r1, v2, theta);
    }

    private static double dreI1dv1(double y, double ksi, double g1, double b1, double ph1, double r1) {
        return r1 * r1 * interReI1(y, ksi, g1, b1, ph1);
    }

    private static double dreI1dv2(double y, double ksi, double r1, double a1, double ph2) {
        return r1 * (-y * R2 * FastMath.sin(theta(ksi, a1, ph2)));
    }

    private static double dreI1dph1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1) {
        return r1 * r1 * v1 * (-g1 * FastMath.sin(ph1) - b1 * FastMath.cos(ph1) + y * FastMath.cos(ph1 + ksi));
    }

    private static double dreI1dph2(double y, double ksi, double r1, double a1, double v2, double ph2) {
        return r1 * (-y * R2 * v2 * FastMath.cos(theta(ksi, a1, ph2)));
    }

    private static double dimI1dv1(double y, double ksi, double g1, double b1, double ph1, double r1) {
        return r1 * r1 * interImI1(y, ksi, g1, b1, ph1);
    }

    private static double dimI1dv2(double y, double ksi, double r1, double a1, double ph2) {
        return r1 * (y * R2 * FastMath.cos(theta(ksi, a1, ph2)));
    }

    private static double dimI1dph1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1) {
        return r1 * r1 * v1 * interReI1(y, ksi, g1, b1, ph1);
    }

    private static double dimI1dph2(double y, double ksi, double r1, double a1, double v2, double ph2) {
        return r1 * (-y * R2 * v2 * FastMath.sin(theta(ksi, a1, ph2)));
    }

    public static double di1dv1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, a1, ph2);
        return (reI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dreI1dv1(y, ksi, g1, b1, ph1, r1) + imI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dimI1dv1(y, ksi, g1, b1, ph1, r1)) / i1(y, ksi, g1, b1, v1, ph1, r1, v2, theta);
    }

    public static double di1dv2(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, a1, ph2);
        return (reI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dreI1dv2(y, ksi, r1, a1, ph2) + imI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dimI1dv2(y, ksi, r1, a1, ph2)) / i1(y, ksi, g1, b1, v1, ph1, r1, v2, theta);
    }

    public static double di1dph1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, a1, ph2);
        return (reI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dreI1dph1(y, ksi, g1, b1, v1, ph1, r1) + imI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dimI1dph1(y, ksi, g1, b1, v1, ph1, r1)) / i1(y, ksi, g1, b1, v1, ph1, r1, v2, theta);
    }

    public static double di1dph2(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, a1, ph2);
        return (reI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dreI1dph2(y, ksi, r1, a1, v2, ph2) + imI1(y, ksi, g1, b1, v1, ph1, r1, v2, theta) * dimI1dph2(y, ksi, r1, a1, v2, ph2)) / i1(y, ksi, g1, b1, v1, ph1, r1, v2, theta);
    }

    public static double di1da1(double y, double ksi, double g1, double b1, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        return -di1dph2(y, ksi, g1, b1, v1, ph1, r1, a1, v2, ph2);
    }

    @Override
    public double eval() {
        return i1(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return di1dv1(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(v2Var)) {
            return di1dv2(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph1Var)) {
            return di1dph1(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph2Var)) {
            return di1dph2(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(a1Var)) {
            return di1da1(y(), ksi(), g1(), b1(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(r1Var)) {
            throw new IllegalArgumentException("Derivative with respect to r1 not implemented");
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_i_closed_1";
    }
}
