/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.AbstractNamedEquationTerm;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractShuntCompensatorEquationTerm extends AbstractNamedEquationTerm<AcVariableType, AcEquationType> {

    protected static final int DV = 0;

    protected final LfShunt shunt;

    protected final Variable<AcVariableType> vVar;

    protected AbstractShuntCompensatorEquationTerm(LfShunt shunt, LfBus bus, VariableSet<AcVariableType> variableSet) {
        super(!Objects.requireNonNull(shunt).isDisabled());
        this.shunt = shunt;
        Objects.requireNonNull(bus);
        Objects.requireNonNull(variableSet);
        vVar = variableSet.getVariable(bus.getNum(), AcVariableType.BUS_V);
    }

    @Override
    public ElementType getElementType() {
        return ElementType.SHUNT_COMPENSATOR;
    }

    @Override
    public int getElementNum() {
        return shunt.getNum();
    }

    protected double v() {
        return sv.get(vVar.getRow());
    }

    @Override
    public int getDerIndex(Variable<AcVariableType> variable) {
        if (variable.equals(vVar)) {
            return DV;
        }
        return DER_ZERO_INDEX;
    }
}
