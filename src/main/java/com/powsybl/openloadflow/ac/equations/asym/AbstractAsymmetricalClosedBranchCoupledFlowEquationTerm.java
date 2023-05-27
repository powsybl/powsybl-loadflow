/**
 * Copyright (c) 2023, Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * Copyright (c) 2023, Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.asym;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.Side;
import com.powsybl.openloadflow.util.ComplexPart;
import com.powsybl.openloadflow.util.Fortescue.SequenceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public abstract class AbstractAsymmetricalClosedBranchCoupledFlowEquationTerm extends AbstractAsymmetricalBranchFlowEquationTerm {

    // positive
    protected final Variable<AcVariableType> v1Var;

    protected final Variable<AcVariableType> v2Var;

    protected final Variable<AcVariableType> ph1Var;

    protected final Variable<AcVariableType> ph2Var;

    // negative
    protected final Variable<AcVariableType> v1VarNegative;

    protected final Variable<AcVariableType> v2VarNegative;

    protected final Variable<AcVariableType> ph1VarNegative;

    protected final Variable<AcVariableType> ph2VarNegative;

    // zero
    protected final Variable<AcVariableType> v1VarZero;

    protected final Variable<AcVariableType> v2VarZero;

    protected final Variable<AcVariableType> ph1VarZero;

    protected final Variable<AcVariableType> ph2VarZero;

    protected final List<Variable<AcVariableType>> variables = new ArrayList<>();

    protected final ComplexPart complexPart;
    protected final Side side;
    protected final SequenceType sequenceType;

    protected AbstractAsymmetricalClosedBranchCoupledFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                                      ComplexPart complexPart, Side side, SequenceType sequenceType) {
        super(branch);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        Objects.requireNonNull(variableSet);
        this.complexPart = Objects.requireNonNull(complexPart);
        this.side = Objects.requireNonNull(side);
        this.sequenceType = Objects.requireNonNull(sequenceType);

        v1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V);
        v2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V);
        ph1Var = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI);
        ph2Var = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI);

        v1VarNegative = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V_NEGATIVE);
        v2VarNegative = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V_NEGATIVE);
        ph1VarNegative = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI_NEGATIVE);
        ph2VarNegative = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI_NEGATIVE);

        v1VarZero = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_V_ZERO);
        v2VarZero = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_V_ZERO);
        ph1VarZero = variableSet.getVariable(bus1.getNum(), AcVariableType.BUS_PHI_ZERO);
        ph2VarZero = variableSet.getVariable(bus2.getNum(), AcVariableType.BUS_PHI_ZERO);

        variables.add(v1Var);
        variables.add(v2Var);
        variables.add(ph1Var);
        variables.add(ph2Var);
        variables.add(v1VarNegative);
        variables.add(v2VarNegative);
        variables.add(ph1VarNegative);
        variables.add(ph2VarNegative);
        variables.add(v1VarZero);
        variables.add(v2VarZero);
        variables.add(ph1VarZero);
        variables.add(ph2VarZero);
    }

    protected static SequenceType getSequenceType(Variable<AcVariableType> variable) {
        switch (variable.getType()) {
            case BUS_V:
            case BUS_PHI:
                return SequenceType.POSITIVE;

            case BUS_V_NEGATIVE:
            case BUS_PHI_NEGATIVE:
                return SequenceType.NEGATIVE;

            case BUS_V_ZERO:
            case BUS_PHI_ZERO:
                return SequenceType.ZERO;

            default:
                throw new IllegalStateException("Unknown variable: " + variable);
        }
    }

    protected static boolean isPhase(Variable<AcVariableType> variable) {
        switch (variable.getType()) {
            case BUS_PHI:
            case BUS_PHI_NEGATIVE:
            case BUS_PHI_ZERO:
                return true;
            default:
                return false;
        }
    }

    protected double v(SequenceType g, Side i) {
        switch (g) {
            case ZERO:
                return i == Side.ONE ? sv.get(v1VarZero.getRow()) : sv.get(v2VarZero.getRow());

            case POSITIVE:
                return i == Side.ONE ? sv.get(v1Var.getRow()) : sv.get(v2Var.getRow());

            case NEGATIVE:
                return i == Side.ONE ? sv.get(v1VarNegative.getRow()) : sv.get(v2VarNegative.getRow());

            default:
                throw new IllegalStateException("Unknown sequence type: " + g);
        }
    }

    protected double ph(SequenceType g, Side i) {
        switch (g) {
            case ZERO:
                return i == Side.ONE ? sv.get(ph1VarZero.getRow()) : sv.get(ph2VarZero.getRow());

            case POSITIVE:
                return i == Side.ONE ? sv.get(ph1Var.getRow()) : sv.get(ph2Var.getRow());

            case NEGATIVE:
                return i == Side.ONE ? sv.get(ph1VarNegative.getRow()) : sv.get(ph2VarNegative.getRow());

            default:
                throw new IllegalStateException("Unknown sequence type: " + g);
        }
    }

    protected double r1() {
        return 1;
    }

    protected double a1() {
        return 0;
    }

    protected double r(Side i) {
        return i == Side.ONE ? r1() : 1.;
    }

    protected double a(Side i) {
        return i == Side.ONE ? a1() : A2;
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }

    public Side getSide(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var) || variable.equals(v1VarZero) || variable.equals(v1VarNegative)
                || variable.equals(ph1Var) || variable.equals(ph1VarZero) || variable.equals(ph1VarNegative)) {
            return Side.ONE;
        } else if (variable.equals(v2Var) || variable.equals(v2VarZero) || variable.equals(v2VarNegative)
                || variable.equals(ph2Var) || variable.equals(ph2VarZero) || variable.equals(ph2VarNegative)) {
            return Side.TWO;
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }
}