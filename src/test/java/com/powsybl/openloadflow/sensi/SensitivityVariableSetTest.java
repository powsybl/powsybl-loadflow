/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class SensitivityVariableSetTest {

    @Test
    void test() {
        SensitivityVariableSet set = new SensitivityVariableSet("id", List.of(new WeightedSensitivityVariable("v1", 3.4)));
        assertEquals("SensitivityVariableSet(id='id', variables=[WeightedSensitivityVariable(id='v1', weight=3.4)])", set.toString());
    }
}
