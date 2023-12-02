/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ShuntCompensatorActiveFlowEquationTerm extends AbstractShuntCompensatorEquationTerm {

    private final List<Variable<AcVariableType>> variables;

    public ShuntCompensatorActiveFlowEquationTerm(AcShuntVector shuntVector, int num, int busNum, VariableSet<AcVariableType> variableSet) {
        super(shuntVector, num, busNum, variableSet);
        variables = List.of(vVar);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    public static double p(double v, double g) {
        return g * v * v;
    }

    public static double dpdv(double v, double g) {
        return 2 * g * v;
    }

    @Override
    public double eval() {
        return shuntVector.p[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(vVar)) {
            return shuntVector.dpdv[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_shunt";
    }
}
