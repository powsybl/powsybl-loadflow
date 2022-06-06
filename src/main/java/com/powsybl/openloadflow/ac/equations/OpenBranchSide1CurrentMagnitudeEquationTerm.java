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
 * @author Gael Macherel <gael.macherel at artelys.com>
 */
public class OpenBranchSide1CurrentMagnitudeEquationTerm extends AbstractOpenSide1BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    private final Variable<AcVariableType> ph2Var;

    public OpenBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                       boolean deriveA1, boolean deriveR1) {
        super(branch, AcVariableType.BUS_V, bus2, variableSet, deriveA1, deriveR1);
        v2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V);
        ph2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI);
    }

    private double v2() {
        return sv.get(v2Var.getRow());
    }

    private double ph2() {
        return sv.get(ph2Var.getRow());
    }

    private static double gres(double y, double ksi, double g1, double b1, double g2, double shunt) {
        return g2 + (y * y * g1 + (b1 * b1 + g1 * g1) * y * FastMath.sin(ksi)) / shunt;
    }

    private static double bres(double y, double ksi, double g1, double b1, double b2, double shunt) {
        return b2 + (y * y * b1 - (b1 * b1 + g1 * g1) * y * FastMath.cos(ksi)) / shunt;
    }

    private static double reI2(double y, double ksi, double g1, double b1, double g2, double b2, double v2, double ph2) {
        double shunt = shunt(y, ksi, g1, b1);
        return R2 * R2 * v2 * (gres(y, ksi, g1, b1, g2, shunt) * FastMath.cos(ph2) - bres(y, ksi, g1, b1, b2, shunt) * FastMath.sin(ph2));
    }

    private static double imI2(double y, double ksi, double g1, double b1, double g2, double b2, double v2, double ph2) {
        double shunt = shunt(y, ksi, g1, b1);
        return R2 * R2 * v2 * (gres(y, ksi, g1, b1, g2, shunt) * FastMath.sin(ph2) + bres(y, ksi, g1, b1, b2, shunt) * FastMath.cos(ph2));
    }

    private static double i2(double y, double ksi, double g1, double b1, double g2, double b2, double v2, double ph2) {
        return FastMath.hypot(reI2(y, ksi, g1, b1, g2, b2, v2, ph2), imI2(y, ksi, g1, b1, g2, b2, v2, ph2));
    }

    private static double dreI2dv2(double y, double ksi, double g1, double b1, double g2, double b2, double ph2) {
        double shunt = shunt(y, ksi, g1, b1);
        return R2 * R2 * (gres(y, ksi, g1, b1, g2, shunt) * FastMath.cos(ph2) - bres(y, ksi, g1, b1, b2, shunt) * FastMath.sin(ph2));
    }

    private static double dimI2dv2(double y, double ksi, double g1, double b1, double g2, double b2, double ph2) {
        double shunt = shunt(y, ksi, g1, b1);
        return R2 * R2 * (gres(y, ksi, g1, b1, g2, shunt) * FastMath.sin(ph2) + bres(y, ksi, g1, b1, b2, shunt) * FastMath.cos(ph2));
    }

    private static double di2dv2(double y, double ksi, double g1, double b1, double g2, double b2, double v2, double ph2) {
        return (reI2(y, ksi, g1, b1, g2, b2, v2, ph2) * dreI2dv2(y, ksi, g1, b1, g2, b2, ph2)
                + imI2(y, ksi, g1, b1, g2, b2, v2, ph2) * dimI2dv2(y, ksi, g1, b1, g2, b2, ph2)) / i2(y, ksi, g1, b1, g2, b2, v2, ph2);
    }

    @Override
    public double eval() {
        return i2(y, ksi, g1, b1, g2, b2, v2(), ph2());
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return di2dv2(y, ksi, g1, b1, g2, b2, v2(), ph2());
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_i_open_1";
    }
}
