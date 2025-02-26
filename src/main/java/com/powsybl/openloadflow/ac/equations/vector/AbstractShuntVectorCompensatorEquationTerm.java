/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.AbstractNamedEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.ElementType;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractShuntVectorCompensatorEquationTerm extends AbstractNamedEquationTerm<AcVariableType, AcEquationType> {

    protected final AcShuntVector shuntVector;

    protected final int num;

    protected final Variable<AcVariableType> vVar;

    protected AbstractShuntVectorCompensatorEquationTerm(AcShuntVector shuntVector, int num, int busNum, VariableSet<AcVariableType> variableSet) {
        super(!Objects.requireNonNull(shuntVector).disabled[num]);
        this.shuntVector = Objects.requireNonNull(shuntVector);
        this.num = num;
        Objects.requireNonNull(variableSet);
        vVar = variableSet.getVariable(busNum, AcVariableType.BUS_V);
    }

    @Override
    public ElementType getElementType() {
        return ElementType.SHUNT_COMPENSATOR;
    }

    @Override
    public int getElementNum() {
        return num;
    }

    protected double v() {
        return sv.get(vVar.getRow());
    }
}
