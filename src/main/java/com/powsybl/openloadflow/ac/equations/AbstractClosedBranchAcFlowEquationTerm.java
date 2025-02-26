/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModelArray;
import com.powsybl.openloadflow.util.Fortescue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractClosedBranchAcFlowEquationTerm extends AbstractBranchAcFlowEquationTerm {

    protected final Variable<AcVariableType> v1Var;

    protected final Variable<AcVariableType> v2Var;

    protected final Variable<AcVariableType> ph1Var;

    protected final Variable<AcVariableType> ph2Var;

    protected final Variable<AcVariableType> a1Var;

    protected final Variable<AcVariableType> r1Var;

    private final boolean isArrayPiModel;

    private final double a1;

    private final double r1;

    protected final List<Variable<AcVariableType>> variables = new ArrayList<>();

    public static AcVariableType getVoltageMagnitudeType(Fortescue.SequenceType sequenceType) {
        return switch (sequenceType) {
            case POSITIVE -> AcVariableType.BUS_V;
            case NEGATIVE -> AcVariableType.BUS_V_NEGATIVE;
            case ZERO -> AcVariableType.BUS_V_ZERO;
        };
    }

    public static AcVariableType getVoltageAngleType(Fortescue.SequenceType sequenceType) {
        return switch (sequenceType) {
            case POSITIVE -> AcVariableType.BUS_PHI;
            case NEGATIVE -> AcVariableType.BUS_PHI_NEGATIVE;
            case ZERO -> AcVariableType.BUS_PHI_ZERO;
        };
    }

    protected AbstractClosedBranchAcFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                     boolean deriveA1, boolean deriveR1, Fortescue.SequenceType sequenceType, AcVectorEngine acVectorEnginee) {
        super(branch, acVectorEnginee);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        Objects.requireNonNull(variableSet);
        AcVariableType vType = getVoltageMagnitudeType(sequenceType);
        AcVariableType angleType = getVoltageAngleType(sequenceType);
        v1Var = variableSet.getVariable(bus1.getNum(), vType);
        v2Var = variableSet.getVariable(bus2.getNum(), vType);
        ph1Var = variableSet.getVariable(bus1.getNum(), angleType);
        ph2Var = variableSet.getVariable(bus2.getNum(), angleType);
        // Just equations with V and phi are vectorized
        if (vType == AcVariableType.BUS_V) {
            acVectorEnginee.v1Var[branch.getNum()] = v1Var;
            acVectorEnginee.v2Var[branch.getNum()] = v2Var;
        }
        if (angleType == AcVariableType.BUS_PHI) {
            acVectorEnginee.ph1Var[branch.getNum()] = ph1Var;
            acVectorEnginee.ph2Var[branch.getNum()] = ph2Var;
        }

        isArrayPiModel = branch.getPiModel() instanceof PiModelArray;
        a1 = isArrayPiModel ? Double.NaN : branch.getPiModel().getA1();
        r1 = isArrayPiModel ? Double.NaN : branch.getPiModel().getR1();

        a1Var = deriveA1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_ALPHA1) : null;
        r1Var = deriveR1 ? variableSet.getVariable(branch.getNum(), AcVariableType.BRANCH_RHO1) : null;
        variables.add(v1Var);
        variables.add(v2Var);
        variables.add(ph1Var);
        variables.add(ph2Var);
        if (a1Var != null) {
            variables.add(a1Var);
        } else {
            acVectorEnginee.a1[branch.getNum()] = branch.getPiModel().getA1();
        }
        if (r1Var != null) {
            variables.add(r1Var);
        }
    }

    @Override
    public void setEquation(Equation<AcVariableType, AcEquationType> equation) {
        super.setEquation(equation);
        if (equation != null) {
            acVectorEnginee.addSupplyingTerm(this);
        }
    }

    public DoubleSupplier getR1Supplier() {
        if (r1Var != null || isArrayPiModel) {
            return () -> r1();
        } else {
            return null;
        }
    }

    public DoubleSupplier getA1Supplier() {
        if (a1Var != null || isArrayPiModel) {
            return () -> a1();
        } else {
            return null;
        }
    }

    public Variable<AcVariableType> getA1Var() {
        return a1Var;
    }

    protected double v1() {
        return sv.get(v1Var.getRow());
    }

    protected double v2() {
        return sv.get(v2Var.getRow());
    }

    protected double ph1() {
        return sv.get(ph1Var.getRow());
    }

    protected double ph2() {
        return sv.get(ph2Var.getRow());
    }

    public double r1() {
        // TODO: Remove test on var row - should not be called if term is inactive
        return r1Var != null && r1Var.getRow() >= 0 ? sv.get(r1Var.getRow()) :
                isArrayPiModel ? element.getPiModel().getR1() : r1;
        // to avoid memory cache miss we don't load the piModel if not necessary
    }

    public double a1() {
        // TODO remove test >0 - should not be called if term is inactive
        return a1Var != null && a1Var.getRow() >= 0 ? sv.get(a1Var.getRow()) :
                isArrayPiModel ? element.getPiModel().getA1() : a1;
        // to avoid memory cache miss we don't load the piModel if not necessary
    }

    public static double theta1(double ksi, double ph1, double a1, double ph2) {
        return ksi - a1 + A2 - ph1 + ph2;
    }

    public static double theta2(double ksi, double ph1, double a1, double ph2) {
        return ksi + a1 - A2 + ph1 - ph2;
    }

    protected abstract double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1);

    @Override
    public double calculateSensi(DenseMatrix dx, int column) {
        Objects.requireNonNull(dx);
        double dph1 = dx.get(ph1Var.getRow(), column);
        double dph2 = dx.get(ph2Var.getRow(), column);
        double dv1 = dx.get(v1Var.getRow(), column);
        double dv2 = dx.get(v2Var.getRow(), column);
        double da1 = a1Var != null ? dx.get(a1Var.getRow(), column) : 0;
        double dr1 = r1Var != null ? dx.get(r1Var.getRow(), column) : 0;
        return calculateSensi(dph1, dph2, dv1, dv2, da1, dr1);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}
