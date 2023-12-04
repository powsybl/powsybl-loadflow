/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;

/**
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
abstract class AbstractOpenSide2BranchVectorAcFlowEquationTerm extends AbstractBranchVectorAcFlowEquationTerm {

    protected final List<Variable<AcVariableType>> variables;

    protected AbstractOpenSide2BranchVectorAcFlowEquationTerm(AcBranchVector branchVector, int branchNum,
                                                              AcVariableType variableType, int bus1Num,
                                                              VariableSet<AcVariableType> variableSet, boolean deriveA1, boolean deriveR1) {
        super(branchVector, branchNum);
        variables = List.of(variableSet.getVariable(bus1Num, variableType));
        if (deriveA1 || deriveR1) {
            throw new IllegalArgumentException("Variable A1 or R1 on open branch not supported: " + branchNum);
        }
    }

    protected static double shunt(double y, double cosKsi, double sinKsi, double g2, double b2) {
        return (g2 + y * sinKsi) * (g2 + y * sinKsi) + (-b2 + y * cosKsi) * (-b2 + y * cosKsi);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}