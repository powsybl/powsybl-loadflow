/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class PropertyTest {

    @Test
    void test() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = Networks.load(network, new FirstSlackBusSelector()).get(0);
        assertNull(lfNetwork.getProperty("a"));
        lfNetwork.setProperty("a", "test");
        assertEquals("test", lfNetwork.getProperty("a"));
        LfBus lfBus = lfNetwork.getBus(0);
        assertNull(lfBus.getProperty("b"));
        lfBus.setProperty("b", "hello");
        assertEquals("hello", lfBus.getProperty("b"));
    }
}
