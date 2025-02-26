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

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class OpenBranchSide1ActiveFlowEquationTerm extends AbstractOpenSide1BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    public OpenBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet, AcVectorEngine acVectorEnginee) {
        super(branch, AcVariableType.BUS_V, bus2, variableSet, acVectorEnginee);
        v2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V);
    }

    private double v2() {
        return sv.get(v2Var.getRow());
    }

    public static double p2(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double v2) {
        double shunt = shunt(y, cosKsi, sinKsi, g1, b1);
        return R2 * R2 * v2 * v2 * (g2 + y * y * g1 / shunt + (b1 * b1 + g1 * g1) * y * sinKsi / shunt);
    }

    public static double dp2dv2(double y, double cosKsi, double sinKsi, double g1, double b1, double g2, double v2) {
        double shunt = shunt(y, cosKsi, sinKsi, g1, b1);
        return 2 * R2 * R2 * v2 * (g2 + y * y * g1 / shunt + (b1 * b1 + g1 * g1) * y * sinKsi / shunt);
    }

    @Override
    public double eval() {
        return p2(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), b1(), g2(), v2());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return dp2dv2(y(), FastMath.cos(ksi()), FastMath.sin(ksi()), g1(), b1(), g2(), v2());
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_open_1";
    }
}
