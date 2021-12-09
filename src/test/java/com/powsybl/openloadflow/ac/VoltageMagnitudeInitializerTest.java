/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class VoltageMagnitudeInitializerTest {

    public static void assertBusVoltage(LfNetwork network, VoltageInitializer initializer, String busId, double vRef, double angleRef) {
        LfBus bus = network.getBusById(busId);
        double v = initializer.getMagnitude(bus);
        double angle = initializer.getAngle(bus);
        assertNotNull(bus);
        assertEquals(vRef, v, 1E-4d);
        assertEquals(angleRef, angle, 1E-2d);
    }

    @Test
    void testEsgTuto1() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        VoltageMagnitudeInitializer initializer = new VoltageMagnitudeInitializer(new DenseMatrixFactory());
        initializer.prepare(lfNetwork);
        assertBusVoltage(lfNetwork, initializer, "VLGEN_0", 1.0208, 0);
        assertBusVoltage(lfNetwork, initializer, "VLHV1_0", 1.0745, 0);
        assertBusVoltage(lfNetwork, initializer, "VLHV2_0", 1.0745, 0);
        assertBusVoltage(lfNetwork, initializer, "VLLOAD_0", 1.076, 0);
    }

    @Test
    void testIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        VoltageMagnitudeInitializer initializer = new VoltageMagnitudeInitializer(new DenseMatrixFactory());
        initializer.prepare(lfNetwork);
        assertBusVoltage(lfNetwork, initializer, "VL1_0", 1.06, 0);
        assertBusVoltage(lfNetwork, initializer, "VL2_0", 1.045, 0);
        assertBusVoltage(lfNetwork, initializer, "VL3_0", 1.01, 0);
        assertBusVoltage(lfNetwork, initializer, "VL4_0", 1.0351, 0);
        assertBusVoltage(lfNetwork, initializer, "VL5_0", 1.0356, 0);
        assertBusVoltage(lfNetwork, initializer, "VL6_0", 1.07, 0);
        assertBusVoltage(lfNetwork, initializer, "VL7_0", 1.074, 0);
        assertBusVoltage(lfNetwork, initializer, "VL8_0", 1.09, 0);
        assertBusVoltage(lfNetwork, initializer, "VL9_0", 1.0723, 0);
        assertBusVoltage(lfNetwork, initializer, "VL10_0", 1.0719, 0);
        assertBusVoltage(lfNetwork, initializer, "VL11_0", 1.0709, 0);
        assertBusVoltage(lfNetwork, initializer, "VL12_0", 1.0701, 0);
        assertBusVoltage(lfNetwork, initializer, "VL13_0", 1.0703, 0);
        assertBusVoltage(lfNetwork, initializer, "VL14_0", 1.0714, 0);
    }

    @Test
    void testNonImpedantBranch() {
        Network network = IeeeCdfNetworkFactory.create14();
        network.getLine("L9-14-1").setX(0);
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        VoltageMagnitudeInitializer initializer = new VoltageMagnitudeInitializer(new DenseMatrixFactory());
        initializer.prepare(lfNetwork);
        assertBusVoltage(lfNetwork, initializer, "VL1_0", 1.06, 0);
        assertBusVoltage(lfNetwork, initializer, "VL2_0", 1.045, 0);
        assertBusVoltage(lfNetwork, initializer, "VL3_0", 1.01, 0);
        assertBusVoltage(lfNetwork, initializer, "VL4_0", 1.0349, 0);
        assertBusVoltage(lfNetwork, initializer, "VL5_0", 1.0354, 0);
        assertBusVoltage(lfNetwork, initializer, "VL6_0", 1.07, 0);
        assertBusVoltage(lfNetwork, initializer, "VL7_0", 1.0733, 0);
        assertBusVoltage(lfNetwork, initializer, "VL8_0", 1.09, 0);
        assertBusVoltage(lfNetwork, initializer, "VL9_0", 1.0708, 0); // equals VL14_0
        assertBusVoltage(lfNetwork, initializer, "VL10_0", 1.0707, 0);
        assertBusVoltage(lfNetwork, initializer, "VL11_0", 1.0703, 0);
        assertBusVoltage(lfNetwork, initializer, "VL12_0", 1.0701, 0);
        assertBusVoltage(lfNetwork, initializer, "VL13_0", 1.0701, 0);
        assertBusVoltage(lfNetwork, initializer, "VL14_0", 1.0708, 0); // equals VL9_0
    }

    @Test
    void testParallelBranch() {
        Network network = IeeeCdfNetworkFactory.create14();
        Line l9101 = network.getLine("L9-10-1");
        double newX = l9101.getX() * 2; // so that result is the same as initial case when doubling line
        l9101.setX(newX);
        network.newLine()
                .setId("L9-10-2")
                .setVoltageLevel1("VL9")
                .setConnectableBus1("B9")
                .setBus1("B9")
                .setVoltageLevel2("VL10")
                .setConnectableBus2("B10")
                .setBus2("B10")
                .setR(0)
                .setX(newX)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        VoltageMagnitudeInitializer initializer = new VoltageMagnitudeInitializer(new DenseMatrixFactory());
        initializer.prepare(lfNetwork);
        assertBusVoltage(lfNetwork, initializer, "VL1_0", 1.06, 0);
        assertBusVoltage(lfNetwork, initializer, "VL2_0", 1.045, 0);
        assertBusVoltage(lfNetwork, initializer, "VL3_0", 1.01, 0);
        assertBusVoltage(lfNetwork, initializer, "VL4_0", 1.0351, 0);
        assertBusVoltage(lfNetwork, initializer, "VL5_0", 1.0356, 0);
        assertBusVoltage(lfNetwork, initializer, "VL6_0", 1.07, 0);
        assertBusVoltage(lfNetwork, initializer, "VL7_0", 1.074, 0);
        assertBusVoltage(lfNetwork, initializer, "VL8_0", 1.09, 0);
        assertBusVoltage(lfNetwork, initializer, "VL9_0", 1.0723, 0);
        assertBusVoltage(lfNetwork, initializer, "VL10_0", 1.0719, 0);
        assertBusVoltage(lfNetwork, initializer, "VL11_0", 1.0709, 0);
        assertBusVoltage(lfNetwork, initializer, "VL12_0", 1.0701, 0);
        assertBusVoltage(lfNetwork, initializer, "VL13_0", 1.0703, 0);
        assertBusVoltage(lfNetwork, initializer, "VL14_0", 1.0714, 0);
    }
}
