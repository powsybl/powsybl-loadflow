/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class OpenBranchSide1ReactiveFlowEquationTerm extends AbstractOpenSide1BranchAcFlowEquationTerm {

    private final Variable<AcVariableType> v2Var;

    public OpenBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1) {
        super(branch, AcVariableType.BUS_V, bus2, variableSet, deriveA1, deriveR1);
        v2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V);
    }

    private double v2(StateVector sv) {
        return sv.get(v2Var.getRow());
    }

    private double q2(StateVector sv) {
        double shunt = shunt();
        double v2 = v2(sv);
        return -R2 * R2 * v2 * v2 * (b2 + y * y * b1 / shunt - (b1 * b1 + g1 * g1) * y * FastMath.cos(ksi) / shunt);
    }

    private double dq2dv2(StateVector sv) {
        double shunt = shunt();
        return -2 * v2(sv) * R2 * R2 * (b2 + y * y * b1 / shunt - (b1 * b1 + g1 * g1) * y * FastMath.cos(ksi) / shunt);
    }

    @Override
    public double eval() {
        return q2(stateVector);
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return dq2dv2(stateVector);
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_q_open_1";
    }
}
