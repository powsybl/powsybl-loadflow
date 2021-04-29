/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.SlackTerminalAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
class NetworkSlackBusSelectorTest {

    private Network network;

    private SlackBusSelector selectorMock;

    private int fallbackBusCount = -1;

    @BeforeEach
    void setUp() {
        network = EurostagTutorialExample1Factory.create();
        MostMeshedSlackBusSelector selectorFallback = new MostMeshedSlackBusSelector();
        selectorMock = buses -> {
            fallbackBusCount = buses.size();
            return selectorFallback.select(buses);
        };
    }

    @Test
    void noExtensionTest() {
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new NetworkSlackBusSelector(network, selectorMock));
        LfNetwork lfNetwork = lfNetworks.stream().filter(n -> n.getNum() == ComponentConstants.MAIN_NUM).findAny().orElse(null);
        assertEquals("VLHV1_0", lfNetwork.getSlackBus().getId());
        assertEquals(4, fallbackBusCount);
    }

    @Test
    void oneExtensionTest() {
        VoltageLevel vlload = network.getVoltageLevel("VLLOAD");
        Load load = network.getLoad("LOAD");
        vlload.newExtension(SlackTerminalAdder.class)
                .withTerminal(load.getTerminal())
                .add();
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new NetworkSlackBusSelector(network, selectorMock));
        LfNetwork lfNetwork = lfNetworks.stream().filter(n -> n.getNum() == ComponentConstants.MAIN_NUM).findAny().orElse(null);
        assertEquals("VLLOAD_0", lfNetwork.getSlackBus().getId());
        assertEquals(-1, fallbackBusCount);
    }

    @Test
    void twoExtensionsTest() {
        VoltageLevel vlload = network.getVoltageLevel("VLLOAD");
        VoltageLevel vlgen = network.getVoltageLevel("VLGEN");
        Load load = network.getLoad("LOAD");
        Generator gen = network.getGenerator("GEN");
        vlload.newExtension(SlackTerminalAdder.class)
                .withTerminal(load.getTerminal())
                .add();
        vlgen.newExtension(SlackTerminalAdder.class)
                .withTerminal(gen.getTerminal())
                .add();
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new NetworkSlackBusSelector(network, selectorMock));
        LfNetwork lfNetwork = lfNetworks.stream().filter(n -> n.getNum() == ComponentConstants.MAIN_NUM).findAny().orElse(null);
        assertEquals("VLLOAD_0", lfNetwork.getSlackBus().getId());
        assertEquals(2, fallbackBusCount);
    }
}
