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
abstract class AbstractOpenSide1BranchVectorAcFlowEquationTerm extends AbstractBranchVectorAcFlowEquationTerm {

    protected final List<Variable<AcVariableType>> variables;

    protected AbstractOpenSide1BranchVectorAcFlowEquationTerm(AcBranchVector branchVector, int branchNum,
                                                              AcVariableType variableType, int bus2Num,
                                                              VariableSet<AcVariableType> variableSet) {
        super(branchVector, branchNum);
        variables = List.of(variableSet.getVariable(bus2Num, variableType));
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}
