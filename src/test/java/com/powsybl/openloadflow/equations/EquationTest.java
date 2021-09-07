/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import com.google.common.testing.EqualsTester;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class EquationTest {

    private EquationSystem<AcVariableType, AcEquationType> equationSystem;

    @BeforeEach
    void setUp() {
        equationSystem = Mockito.mock(EquationSystem.class);
        LfNetwork network = Mockito.mock(LfNetwork.class);
        LfBus bus = Mockito.mock(LfBus.class);
        Mockito.when(network.getBus(Mockito.anyInt())).thenReturn(bus);
        Mockito.when(bus.getId()).thenReturn("bus1");
    }

    @Test
    void testEquals() {
        new EqualsTester()
                .addEqualityGroup(new Equation<>(0, AcEquationType.BUS_P, equationSystem), new Equation<>(0, AcEquationType.BUS_P, equationSystem))
                .addEqualityGroup(new Equation<>(1, AcEquationType.BUS_Q, equationSystem), new Equation<>(1, AcEquationType.BUS_Q, equationSystem))
                .testEquals();
    }

    @Test
    void testToString() {
        assertEquals("Equation(num=0, type=BUS_P, column=-1)", new Equation<>(0, AcEquationType.BUS_P, equationSystem).toString());
        assertEquals("Equation(num=1, type=ZERO_Q, column=-1)", new Equation<>(1, AcEquationType.ZERO_Q, equationSystem).toString());
    }
}
