/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcVectorizedEquationSystemCreationContext extends AcEquationSystemCreationContext {

    private final AcNetworkVector networkVector;

    public AcVectorizedEquationSystemCreationContext(EquationSystem<AcVariableType, AcEquationType> equationSystem, AcNetworkVector networkVector) {
        super(equationSystem);
        this.networkVector = Objects.requireNonNull(networkVector);
    }

    public AcNetworkVector getNetworkVector() {
        return networkVector;
    }
}
