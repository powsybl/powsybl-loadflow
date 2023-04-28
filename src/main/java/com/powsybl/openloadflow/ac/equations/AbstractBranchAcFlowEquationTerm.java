/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.AbstractNamedEquationTerm;
import com.powsybl.openloadflow.network.ElementType;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractBranchAcFlowEquationTerm extends AbstractNamedEquationTerm<AcVariableType, AcEquationType> {

    protected final AcBranchVector branchVector;

    protected final int num;

    protected AbstractBranchAcFlowEquationTerm(AcBranchVector branchVector, int num) {
        super(!Objects.requireNonNull(branchVector).disabled[num]);
        this.branchVector = Objects.requireNonNull(branchVector);
        this.num = num;
    }

    @Override
    public ElementType getElementType() {
        return ElementType.BRANCH;
    }

    @Override
    public int getElementNum() {
        return num;
    }
}
