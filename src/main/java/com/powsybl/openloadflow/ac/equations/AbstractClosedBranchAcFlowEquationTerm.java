/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractClosedBranchAcFlowEquationTerm extends AbstractBranchAcFlowEquationTerm {

    protected final Variable<AcVariableType> v1Var;

    protected final Variable<AcVariableType> v2Var;

    protected final Variable<AcVariableType> ph1Var;

    protected final Variable<AcVariableType> ph2Var;

    protected final Variable<AcVariableType> a1Var;

    protected final Variable<AcVariableType> r1Var;

    protected final List<Variable<AcVariableType>> variables = new ArrayList<>();

    protected AbstractClosedBranchAcFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1) {
        super(branch);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        Objects.requireNonNull(variableSet);
        v1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V);
        v2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V);
        ph1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI);
        ph2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI);
        a1Var = deriveA1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_ALPHA1) : null;
        r1Var = deriveR1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_RHO1) : null;
        variables.add(v1Var);
        variables.add(v2Var);
        variables.add(ph1Var);
        variables.add(ph2Var);
        if (a1Var != null) {
            variables.add(a1Var);
        }
        if (r1Var != null) {
            variables.add(r1Var);
        }
    }

    protected double v1(StateVector sv) {
        return sv.get(v1Var.getRow());
    }

    protected double v2(StateVector sv) {
        return sv.get(v2Var.getRow());
    }

    protected double ph1(StateVector sv) {
        return sv.get(ph1Var.getRow());
    }

    protected double ph2(StateVector sv) {
        return sv.get(ph2Var.getRow());
    }

    protected double r1(StateVector sv) {
        return r1Var != null ? sv.get(r1Var.getRow()) : branch.getPiModel().getR1();
    }

    protected double a1(StateVector sv) {
        return a1Var != null ? sv.get(a1Var.getRow()) : branch.getPiModel().getA1();
    }

    protected abstract double calculateSensi(double ph1, double ph2, double v1, double v2, double a1, double r1);

    @Override
    public double calculateSensi(DenseMatrix x, int column) {
        Objects.requireNonNull(x);
        double ph1 = x.get(ph1Var.getRow(), column);
        double ph2 = x.get(ph2Var.getRow(), column);
        double v1 = x.get(v1Var.getRow(), column);
        double v2 = x.get(v2Var.getRow(), column);
        double a1 = a1Var != null ? x.get(a1Var.getRow(), column) : branch.getPiModel().getA1();
        double r1 = r1Var != null ? x.get(r1Var.getRow(), column) : branch.getPiModel().getR1();
        return calculateSensi(ph1, ph2, v1, v2, a1, r1);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}
