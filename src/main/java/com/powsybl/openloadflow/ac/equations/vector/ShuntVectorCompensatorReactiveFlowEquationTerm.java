/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;
import java.util.Objects;

import static com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ShuntVectorCompensatorReactiveFlowEquationTerm extends AbstractShuntVectorCompensatorEquationTerm {

    private Variable<AcVariableType> bVar;

    private final List<Variable<AcVariableType>> variables;

    public ShuntVectorCompensatorReactiveFlowEquationTerm(AcShuntVector shuntVector, int num, int busNum, VariableSet<AcVariableType> variableSet, boolean deriveB) {
        super(shuntVector, num, busNum, variableSet);
        if (deriveB) {
            bVar = variableSet.getVariable(num, AcVariableType.SHUNT_B);
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
        return bVar != null ? sv.get(bVar.getRow()) : shuntVector.b[num];
    }

    @Override
    public double eval() {
        return shuntVector.q[num];
    }

    @Override
    public double der(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(vVar)) {
            return shuntVector.dqdv[num];
        } else if (variable.equals(bVar)) {
            return shuntVector.dqdb[num];
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        double dv = dx.get(vVar.getRow(), column);
        double db = bVar != null ? dx.get(bVar.getRow(), column) : 0;
        return ShuntCompensatorReactiveFlowEquationTerm.calculateSensi(v(), b(), dv, db);
    }

    @Override
    protected String getName() {
        return "ac_q_shunt";
    }
}
