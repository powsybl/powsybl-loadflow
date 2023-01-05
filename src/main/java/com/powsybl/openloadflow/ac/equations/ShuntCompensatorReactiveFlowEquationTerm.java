/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class ShuntCompensatorReactiveFlowEquationTerm extends AbstractShuntCompensatorEquationTerm {

    protected static final int DB = 1;

    private Variable<AcVariableType> bVar;

    private final List<Variable<AcVariableType>> variables;

    public ShuntCompensatorReactiveFlowEquationTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet, boolean deriveB) {
        super(shunt, bus, variableSet);
        if (deriveB) {
            bVar = variableSet.getVariable(shunt.getNum(), AcVariableType.SHUNT_B);
            variables = List.of(vVar, bVar);
        } else {
            variables = List.of(vVar);
        }
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    private double b() {
        return bVar != null ? sv.get(bVar.getRow()) : element.getB();
    }

    private static double q(double v, double b) {
        return -b * v * v;
    }

    private static double dqdv(double v, double b) {
        return -2 * b * v;
    }

    private static double dqdb(double v) {
        return -v * v;
    }

    @Override
    public double eval() {
        return q(v(), b());
    }

    @Override
    public int getDerIndex(Variable<AcVariableType> variable) {
        if (variable.equals(bVar)) {
            return DB;
        }
        return super.getDerIndex(variable);
    }

    @Override
    public double der(int index) {
        if (index == DV) {
            return dqdv(v(), b());
        } else if (index == DB) {
            return dqdb(v());
        }
        return super.der(index);
    }

    @Override
    protected String getName() {
        return "ac_q_shunt";
    }
}
