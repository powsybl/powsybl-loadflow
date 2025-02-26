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
import net.jafama.FastMath;

import java.util.Objects;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
@SuppressWarnings("squid:S00107")
public class OpenBranchSide2CurrentMagnitudeEquationTerm extends AbstractOpenSide2BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v1Var;

    private final Variable<AcVariableType> ph1Var;

    private Variable<AcVariableType> r1Var;

    public OpenBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet,
                                                       boolean deriveR1, AcVectorEngine acVectorEnginee) {
        super(branch, AcVariableType.BUS_V, bus1, variableSet, acVectorEnginee);
        v1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V);
        ph1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI);
        if (deriveR1) {
            r1Var = variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_RHO1);
        }
    }

    private double v1() {
        return sv.get(v1Var.getRow());
    }

    private double ph1() {
        return sv.get(ph1Var.getRow());
    }

    private double r1() {
        return r1Var != null ? sv.get(r1Var.getRow()) : element.getPiModel().getR1();
    }

    private static double gres(double y, double sinksi, double g1, double g2, double b2, double shunt) {
        return g1 + (y * y * g2 + (b2 * b2 + g2 * g2) * y * sinksi) / shunt;
    }

    private static double bres(double y, double cosKsi, double b1, double g2, double b2, double shunt) {
        return b1 + (y * y * b2 - (b2 * b2 + g2 * g2) * y * cosKsi) / shunt;
    }

    private static double reI1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double v1, double ph1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return r1 * r1 * v1 * (gres(y, sinKsi, g1, g2, b2, shunt) * FastMath.cos(ph1) - bres(y, cosKsi, b1, g2, b2, shunt) * FastMath.sin(ph1));
    }

    private static double imI1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double v1, double ph1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return r1 * r1 * v1 * (gres(y, sinKsi, g1, g2, b2, shunt) * FastMath.sin(ph1) + bres(y, cosKsi, b1, g2, b2, shunt) * FastMath.cos(ph1));
    }

    private static double i1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double v1, double ph1, double r1) {
        return FastMath.hypot(reI1(y, cosKsi, sinKsi, g1, b1, g2, b2, v1, ph1, r1), imI1(y, cosKsi, sinKsi, g1, b1, g2, b2, v1, ph1, r1));
    }

    private static double dreI1dv1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double ph1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return r1 * r1 * (gres(y, sinKsi, g1, g2, b2, shunt) * FastMath.cos(ph1) - bres(y, cosKsi, b1, g2, b2, shunt) * FastMath.sin(ph1));
    }

    private static double dimI1dv1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double ph1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return r1 * r1 * (gres(y, sinKsi, g1, g2, b2, shunt) * FastMath.sin(ph1) + bres(y, cosKsi, b1, g2, b2, shunt) * FastMath.cos(ph1));
    }

    public static double di1dv1(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double b2, double v1, double ph1, double r1) {
        return (reI1(y, cosKsi, sinKsi, g1, b1, g2, b2, v1, ph1, r1) * dreI1dv1(y, cosKsi, sinKsi, g1, b1, g2, b2, ph1, r1)
                + imI1(y, cosKsi, sinKsi, g1, b1, g2, b2, v1, ph1, r1) * dimI1dv1(y, cosKsi, sinKsi, g1, b1, g2, b2, ph1, r1)) / i1(y, cosKsi, sinKsi, g1, b1, g2, b2, v1, ph1, r1);
    }

    @Override
    public double eval() {
        return i1(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), b1(), g2(), b2(), v1(), ph1(), r1());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return di1dv1(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), b1(), g2(), b2(), v1(), ph1(), r1());
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
