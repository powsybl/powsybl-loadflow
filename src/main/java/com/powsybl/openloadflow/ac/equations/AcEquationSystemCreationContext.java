/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.EquationSystem;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcEquationSystemCreationContext {

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    public AcEquationSystemCreationContext(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
    }

    public EquationSystem<AcVariableType, AcEquationType> getEquationSystem() {
        return equationSystem;
    }
}
