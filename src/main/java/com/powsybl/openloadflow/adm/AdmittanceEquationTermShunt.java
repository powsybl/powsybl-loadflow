/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;

import java.util.List;
import java.util.Objects;

/**
 *         v1x   v1y
 *          |     |
 * i1x -  [ g  -b ]
 * i2y -  [ b   g ]
 *
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AdmittanceEquationTermShunt extends AbstractElementEquationTerm<LfBus, AdmittanceVariableType, AdmittanceEquationType> {

    protected final Variable<AdmittanceVariableType> v1xVar;

    protected final Variable<AdmittanceVariableType> v1yVar;

    protected final List<Variable<AdmittanceVariableType>> variables;

    protected double g;

    protected double b;

    protected boolean real;

    public AdmittanceEquationTermShunt(LfBus bus, VariableSet<AdmittanceVariableType> variableSet, double g, double b, boolean real) {
        super(bus);
        Objects.requireNonNull(variableSet);

        v1xVar = variableSet.getVariable(bus.getNum(), AdmittanceVariableType.BUS_ADM_VX);
        v1yVar = variableSet.getVariable(bus.getNum(), AdmittanceVariableType.BUS_ADM_VY);
        variables = List.of(v1xVar, v1yVar);

        this.g = g;
        this.b = b;
        this.real = real;
    }

    public List<Variable<AdmittanceVariableType>> getVariables() {
        return variables;
    }

    @Override
    public double eval() {
        throw new UnsupportedOperationException("Not needed");
    }

    @Override
    public double der(Variable<AdmittanceVariableType> variable) {
        if (variable.equals(v1xVar)) {
            return real ? g : b;
        } else if (variable.equals(v1yVar)) {
            return real ? -b : g;
        } else {
            throw new IllegalArgumentException("Unknown variable " + variable);
        }
    }

    @Override
    protected String getName() {
        return "adm_shunt";
    }
}
