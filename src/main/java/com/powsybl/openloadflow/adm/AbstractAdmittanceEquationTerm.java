/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModel;

import java.util.List;
import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public abstract class AbstractAdmittanceEquationTerm extends AbstractElementEquationTerm<LfBranch, VariableType, EquationType> {

    protected final Variable<VariableType> v1rVar;

    protected final Variable<VariableType> v1iVar;

    protected final Variable<VariableType> v2rVar;

    protected final Variable<VariableType> v2iVar;

    protected final List<Variable<VariableType>> variables;

    protected double rho;

    protected double zInvSquare;

    protected double r;

    protected double x;

    protected double cosA;

    protected double sinA;

    protected double gPi1;

    protected double bPi1;

    protected double gPi2;

    protected double bPi2;

    protected AbstractAdmittanceEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<VariableType> variableSet) {
        super(branch);
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        Objects.requireNonNull(variableSet);

        v1rVar = variableSet.getVariable(bus1.getNum(), VariableType.BUS_VR);
        v2rVar = variableSet.getVariable(bus2.getNum(), VariableType.BUS_VR);
        v1iVar = variableSet.getVariable(bus1.getNum(), VariableType.BUS_VI);
        v2iVar = variableSet.getVariable(bus2.getNum(), VariableType.BUS_VI);

        variables = List.of(v1rVar, v2rVar, v1iVar, v2iVar);

        PiModel piModel = branch.getPiModel();
        if (piModel.getX() == 0) {
            throw new IllegalArgumentException("Branch '" + branch.getId() + "' has reactance equal to zero");
        }
        rho = piModel.getR1();
        if (piModel.getZ() == 0) {
            throw new IllegalArgumentException("Branch '" + branch.getId() + "' has Z equal to zero");
        }

        r = piModel.getR();
        x = piModel.getX();
        double z = Math.sqrt(r * r + x * x);
        zInvSquare = 1 / (z * z);

        double alpha = piModel.getA1();
        cosA = Math.cos(Math.toRadians(alpha));
        sinA = Math.sin(Math.toRadians(alpha));

        gPi1 = piModel.getG1();
        bPi1 = piModel.getB1();
        gPi2 = piModel.getG2();
        bPi2 = piModel.getB2();
    }

    @Override
    public List<Variable<VariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        throw new UnsupportedOperationException("Not needed");
    }
}
