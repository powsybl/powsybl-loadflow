/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.NetworkBuffer;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

import java.util.Collections;
import java.util.List;

/**
 * @author Gael Macherel <gael.macherel at artelys.com>
 */
abstract class AbstractOpenSide1BranchAcFlowEquationTerm extends AbstractBranchAcFlowEquationTerm {

    protected final List<Variable<AcVariableType>> variables;

    protected AbstractOpenSide1BranchAcFlowEquationTerm(LfBranch branch, AcVariableType variableType,
                                                   LfBus bus, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1) {
        super(branch.getNum());
        variables = Collections.singletonList(variableSet.create(bus.getNum(), variableType));

        if (deriveA1 || deriveR1) {
            throw new IllegalArgumentException("Variable A1 or R1 on open branch not supported: " + branch.getId());
        }
    }

    protected double getShunt(NetworkBuffer<AcVariableType, AcEquationType> buf) {
        return  (buf.g1[num] + buf.y[num] * buf.sinKsi[num]) * (buf.g1[num] + buf.y[num] * buf.sinKsi[num]) + (-buf.b1[num] + buf.y[num] * buf.cosKsi[num]) * (-buf.b1[num] + buf.y[num] * buf.cosKsi[num]);
    }

    @Override
    public List<Variable<AcVariableType>> getVariables() {
        return variables;
    }
}
