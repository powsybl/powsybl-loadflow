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
public class ClosedBranchSide2CurrentMagnitudeEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                         boolean deriveA1, boolean deriveR1, AcVectorEngine acVectorEnginee) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1, Fortescue.SequenceType.POSITIVE, acVectorEnginee);
    }

    public static double calculateSensi(double y, double ksi, double g2, double b2,
                                        double v1, double ph1, double r1, double a1, double v2, double ph2,
                                        double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        if (dr1 != 0) {
            throw new IllegalArgumentException("Derivative with respect to r1 not implemented");
        }
        return di2dph1(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2) * dph1
                + di2dph2(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2) * dph2
                + di2dv1(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2) * dv1
                + di2dv2(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2) * dv2
                + di2da1(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2) * da1;
    }

    @Override
    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        return calculateSensi(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2(), dph1, dph2, dv1, dv2, da1, dr1);
    }

    private static double theta(double ksi, double ph1, double a1) {
        return ksi + a1 - A2 + ph1;
    }

    private static double interReI2(double y, double ksi, double g2, double b2, double ph2) {
        return g2 * FastMath.cos(ph2) - b2 * FastMath.sin(ph2) + y * FastMath.sin(ph2 + ksi);
    }

    private static double interImI2(double y, double ksi, double g2, double b2, double ph2) {
        return g2 * FastMath.sin(ph2) + b2 * FastMath.cos(ph2) - y * FastMath.cos(ph2 + ksi);
    }

    private static double reI2(double y, double ksi, double g2, double b2, double v1, double r1, double v2, double ph2, double theta) {
        return R2 * (R2 * v2 * interReI2(y, ksi, g2, b2, ph2) - y * r1 * v1 * FastMath.sin(theta));
    }

    private static double imI2(double y, double ksi, double g2, double b2, double v1, double r1, double v2, double ph2, double theta) {
        return R2 * (R2 * v2 * interImI2(y, ksi, g2, b2, ph2) + y * r1 * v1 * FastMath.cos(theta));
    }

    private static double i2(double y, double ksi, double g2, double b2, double v1, double r1, double v2, double ph2, double theta) {
        return FastMath.hypot(reI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta), imI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta));
    }

    public static double i2(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, ph1, a1);
        return i2(y, ksi, g2, b2, v1, r1, v2, ph2, theta);
    }

    private static double dreI2dv2(double y, double ksi, double g2, double b2, double ph2) {
        return R2 * R2 * interReI2(y, ksi, g2, b2, ph2);
    }

    private static double dreI2dv1(double y, double r1, double theta) {
        return R2 * (-y * r1 * FastMath.sin(theta));
    }

    private static double dreI2dph2(double y, double ksi, double g2, double b2, double v2, double ph2) {
        return R2 * R2 * v2 * (-g2 * FastMath.sin(ph2) - b2 * FastMath.cos(ph2) + y * FastMath.cos(ph2 + ksi));
    }

    private static double dreI2dph1(double y, double ksi, double v1, double ph1, double r1, double a1) {
        return R2 * (-y * r1 * v1 * FastMath.cos(theta(ksi, ph1, a1)));
    }

    private static double dimI2dv2(double y, double ksi, double g2, double b2, double ph2) {
        return R2 * R2 * interImI2(y, ksi, g2, b2, ph2);
    }

    private static double dimI2dv1(double y, double ksi, double ph1, double r1, double a1) {
        return R2 * (y * r1 * FastMath.cos(theta(ksi, ph1, a1)));
    }

    private static double dimI2dph2(double y, double ksi, double g2, double b2, double v2, double ph2) {
        return R2 * R2 * v2 * interReI2(y, ksi, g2, b2, ph2);
    }

    private static double dimI2dph1(double y, double v1, double r1, double theta) {
        return R2 * (-y * r1 * v1 * FastMath.sin(theta));
    }

    public static double di2dv2(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, ph1, a1);
        return (reI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dreI2dv2(y, ksi, g2, b2, ph2) + imI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dimI2dv2(y, ksi, g2, b2, ph2)) / i2(y, ksi, g2, b2, v1, r1, v2, ph2, theta);
    }

    public static double di2dv1(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, ph1, a1);
        return (reI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dreI2dv1(y, r1, theta) + imI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dimI2dv1(y, ksi, ph1, r1, a1)) / i2(y, ksi, g2, b2, v1, r1, v2, ph2, theta);
    }

    public static double di2dph2(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, ph1, a1);
        return (reI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dreI2dph2(y, ksi, g2, b2, v2, ph2) + imI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dimI2dph2(y, ksi, g2, b2, v2, ph2)) / i2(y, ksi, g2, b2, v1, r1, v2, ph2, theta);
    }

    public static double di2dph1(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        double theta = theta(ksi, ph1, a1);
        return (reI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dreI2dph1(y, ksi, v1, ph1, r1, a1) + imI2(y, ksi, g2, b2, v1, r1, v2, ph2, theta) * dimI2dph1(y, v1, r1, theta)) / i2(y, ksi, g2, b2, v1, r1, v2, ph2, theta);
    }

    public static double di2da1(double y, double ksi, double g2, double b2, double v1, double ph1, double r1, double a1, double v2, double ph2) {
        return di2dph1(y, ksi, g2, b2, v1, ph1, r1, a1, v2, ph2);
    }

    @Override
    public double eval() {
        return i2(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return di2dv1(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(v2Var)) {
            return di2dv2(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph1Var)) {
            return di2dph1(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(ph2Var)) {
            return di2dph2(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
        } else if (variable.equals(a1Var)) {
            return di2da1(y(), ksi(), g2(), b2(), v1(), ph1(), r1(), a1(), v2(), ph2());
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
