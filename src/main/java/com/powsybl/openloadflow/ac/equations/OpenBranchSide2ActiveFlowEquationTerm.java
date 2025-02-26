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
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenBranchSide2ActiveFlowEquationTerm extends AbstractOpenSide2BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v1Var;

    public OpenBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, VariableSet<AcVariableType> variableSet, AcVectorEngine acVectorEnginee) {
        super(branch, AcVariableType.BUS_V, bus1, variableSet, acVectorEnginee);
        v1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V);
    }

    private double v1() {
        return sv.get(v1Var.getRow());
    }

    private double r1() {
        return element.getPiModel().getR1();
    }

    public static double p1(double y, double cosKsi, double sinKsi, double g1, double g2, double b2, double v1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return r1 * r1 * v1 * v1 * (g1 + y * y * g2 / shunt + (b2 * b2 + g2 * g2) * y * sinKsi / shunt);
    }

    public static double dp1dv1(double y, double cosKsi, double sinKsi, double g1, double g2, double b2, double v1, double r1) {
        double shunt = shunt(y, cosKsi, sinKsi, g2, b2);
        return 2 * r1 * r1 * v1 * (g1 + y * y * g2 / shunt + (b2 * b2 + g2 * g2) * y * sinKsi / shunt);
    }

    @Override
    public double eval() {
        return p1(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), g2(), b2(), v1(), r1());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return dp1dv1(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), g2(), b2(), v1(), r1());
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_open_2";
    }
}
