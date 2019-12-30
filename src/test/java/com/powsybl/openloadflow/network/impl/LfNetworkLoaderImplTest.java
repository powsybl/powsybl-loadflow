/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.openloadflow.network.AbstractLoadFlowNetworkFactory;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfNetworkLoaderImplTest extends AbstractLoadFlowNetworkFactory {

    private Network network;

    private Generator g;

    @BeforeEach
    public void setUp() {
        network = Network.create("test", "code");
        Bus b = createBus(network, "b", 380);
        Bus b2 = createBus(network, "b2", 380);
        createLine(network, b, b2, "l", 1);
        g = createGenerator(b, "g", 10, 400);
        g.addExtension(ActivePowerControl.class, new ActivePowerControl<>(g, true, 30));
    }

    @Test
    public void initialTest() {
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        LfGenerator lfGenerator = lfNetwork.getBus(0).getGenerators().get(0);
        assertEquals("g", lfGenerator.getId());
        assertTrue(lfGenerator.isParticipating());
    }

    @Test
    public void generatorNegativeActivePowerTargetTest() {
        // targetP < 0, generator is discarded from active power control
        g.setTargetP(-10);
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        assertFalse(lfNetwork.getBus(0).getGenerators().get(0).isParticipating());
    }

    @Test
    public void generatorActivePowerTargetGreaterThanMaxTest() {
        // targetP > maxP, generator is discarded from active power control
        g.setTargetP(10);
        g.setMaxP(5);
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        assertFalse(lfNetwork.getBus(0).getGenerators().get(0).isParticipating());
    }

    @Test
    public void generatorReactiveRangeTooSmallTest() {
        // generators with a too small reactive range cannot control voltage
        g.newReactiveCapabilityCurve()
                .beginPoint()
                .setP(5)
                .setMinQ(6)
                .setMaxQ(6.0000001)
                .endPoint()
                .beginPoint()
                .setP(14)
                .setMinQ(7)
                .setMaxQ(7.00000001)
                .endPoint()
                .add();
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        assertFalse(lfNetwork.getBus(0).hasVoltageControl());
    }

    @Test
    public void generatorNotStartedTest() {
        // targetP is zero and minP > 0, meansn generator is not started and cannot control voltage
        g.setTargetP(0);
        g.setMinP(1);
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        assertFalse(lfNetwork.getBus(0).hasVoltageControl());
    }
}
