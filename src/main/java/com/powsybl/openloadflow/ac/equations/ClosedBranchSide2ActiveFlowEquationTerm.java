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
import com.powsybl.openloadflow.util.Evaluable;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.A2;
import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class ClosedBranchSide2ActiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double a1, double r1) {
        return dp2dph1() * dph1 + dp2dph2() * dph2 + dp2dv1() * dv1 + dp2dv2() * dv2;
    }

    private double theta() {
        return ksi + a1() - A2 + ph1() - ph2();
    }

    private double p2() {
        return R2 * v2() * (g2 * R2 * v2() - y * r1() * v1() * FastMath.sin(theta()) + y * R2 * v2() * FastMath.sin(ksi));
    }

    private double dp2dv1() {
        return -y * r1() * R2 * v2() * FastMath.sin(theta());
    }

    private double dp2dv2() {
        return R2 * (2 * g2 * R2 * v2() - y * r1() * v1() * FastMath.sin(theta()) + 2 * y * R2 * v2() * FastMath.sin(ksi));
    }

    private double dp2dph1() {
        return -y * r1() * R2 * v1() * v2() * FastMath.cos(theta());
    }

    private double dp2dph2() {
        return -dp2dph1();
    }

    private double dp2da1() {
        return dp2dph1();
    }

    private double dp2dr1() {
        return -y * R2 * v1() * v2() * FastMath.sin(theta());
    }

    @Override
    public double eval() {
        return p2();
    }

    @Override
    public Evaluable der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return this::dp2dv1;
        } else if (variable.equals(v2Var)) {
            return this::dp2dv2;
        } else if (variable.equals(ph1Var)) {
            return this::dp2dph1;
        } else if (variable.equals(ph2Var)) {
            return this::dp2dph2;
        } else if (variable.equals(a1Var)) {
            return this::dp2da1;
        } else if (variable.equals(r1Var)) {
            return this::dp2dr1;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_closed_2";
    }
}
