/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

import java.util.List;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
abstract class AbstractOpenSide1BranchAcFlowEquationTerm extends AbstractBranchAcFlowEquationTerm {

    protected final List<Variable<AcVariableType>> variables;

    protected AbstractOpenSide1BranchAcFlowEquationTerm(LfBranch branch, AcVariableType variableType,
                                                        LfBus bus, VariableSet<AcVariableType> variableSet, AcVectorEngine acVectorEnginee) {
        super(branch, acVectorEnginee);
        variables = List.of(variableSet.getVariable(bus.getNum(), variableType));
    }

    protected static double shunt(double y, double cosKsi, double sinKsi, double g1, double b1) {
        return (g1 + y * sinKsi) * (g1 + y * sinKsi) + (-b1 + y * cosKsi) * (-b1 + y * cosKsi);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}
