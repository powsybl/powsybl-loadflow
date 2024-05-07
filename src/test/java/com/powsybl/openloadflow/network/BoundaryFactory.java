/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.*;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class BoundaryFactory extends AbstractLoadFlowNetworkFactory {

    /**
     *  g1     dl1
     *  |       |
     *  b1 ---- b2
     *      l1
     */
    public static Network create() {
        Network network = Network.create("dl", "test");
        Substation s1 = network.newSubstation()
                .setId("S1")
                .add();
        Substation s2 = network.newSubstation()
                .setId("S2")
                .add();
        VoltageLevel vl1 = s1.newVoltageLevel()
                .setId("vl1")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        vl1.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(101.3664)
                .setTargetV(390)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();
        VoltageLevel vl2 = s2.newVoltageLevel()
                .setId("vl2")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        vl2.newDanglingLine()
                .setId("dl1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setR(0.7)
                .setX(1)
                .setG(Math.pow(10, -6))
                .setB(3 * Math.pow(10, -6))
                .setP0(101)
                .setQ0(150)
                .newGeneration()
                .setTargetP(0)
                .setTargetQ(0)
                .setTargetV(390)
                .setVoltageRegulationOn(false)
                .add()
                .add();
        network.newLine()
                .setId("l1")
                .setBus1("b1")
                .setBus2("b2")
                .setR(1)
                .setX(3)
                .add();
        return network;
    }

    /**
     *  g1     dl1      load3
     *  |       |        |
     *  b1 ---- b2 ----- b3
     *      l1       l13
     */
    public static Network createWithLoad() {

        Network network = create();

        Substation s3 = network.newSubstation()
                .setId("S3")
                .add();
        VoltageLevel vl3 = s3.newVoltageLevel()
                .setId("vl3")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl3.getBusBreakerView().newBus()
                .setId("b3")
                .add();
        vl3.newLoad()
                .setId("load3")
                .setBus("b3")
                .setP0(10.0)
                .setQ0(5.0)
                .add();

        network.newLine()
                .setId("l13")
                .setBus1("b1")
                .setBus2("b3")
                .setR(10)
                .setX(3)
                .add();
        network.newLine()
                .setId("l32")
                .setBus1("b3")
                .setBus2("b2")
                .setR(10)
                .setX(10)
                .add();

        network.getDanglingLine("dl1").setP0(91);

        return network;
    }

    /**
     * g1 + load1                 g4 + load4
     *  |                          |
     *  b1 ----- b2 ----- b3 ----- b4
     *     l12      l23     l34
     */
    public static Network createWithXnode() {

        Network network = Network.create("xnode-network", "test");

        Substation s1 = network.newSubstation()
                .setId("S1")
                .add();
        VoltageLevel vl1 = s1.newVoltageLevel()
                .setId("vl1")
                .setNominalV(380)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        vl1.newLoad()
                .setId("load1")
                .setBus("b1")
                .setP0(50.0)
                .setQ0(50.0)
                .add();
        vl1.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(100)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();

        Substation s2 = network.newSubstation()
                .setId("S2")
                .add();
        VoltageLevel vl2 = s2.newVoltageLevel()
                .setId("vl2")
                .setNominalV(380)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("xnode")
                .add();

        Substation s3 = network.newSubstation()
                .setId("S3")
                .add();
        VoltageLevel vl3 = s3.newVoltageLevel()
                .setId("vl3")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl3.getBusBreakerView().newBus()
                .setId("b3")
                .add();

        Substation s4 = network.newSubstation()
                .setId("S4")
                .add();
        VoltageLevel vl4 = s4.newVoltageLevel()
                .setId("vl4")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl4.getBusBreakerView().newBus()
                .setId("b4")
                .add();
        vl4.newLoad()
                .setId("load4")
                .setBus("b4")
                .setP0(40.0)
                .setQ0(40.0)
                .add();
        vl4.newGenerator()
                .setId("g4")
                .setConnectableBus("b4")
                .setBus("b4")
                .setTargetP(20)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();

        network.newLine()
                .setId("l12")
                .setBus1("b1")
                .setBus2("xnode")
                .setR(0.0)
                .setX(0.1)
                .add();
        network.newLine()
                .setId("l23")
                .setBus1("xnode")
                .setBus2("b3")
                .setR(0)
                .setX(0.08)
                .add();
        network.newLine()
                .setId("l34")
                .setBus1("b3")
                .setBus2("b4")
                .setR(0)
                .setX(1.0)
                .add();

        return network;
    }

    /**
     * g1 + load1                    g4 + load4
     *  |                               |
     *  b1 ----- (xnode) ----- b3 ----- b4
     *           t13               l34
     */
    public static Network createWithTieLine() {

        Network network = Network.create("xnode-network", "test");

        Substation s1 = network.newSubstation()
                .setId("S1")
                .add();
        VoltageLevel vl1 = s1.newVoltageLevel()
                .setId("vl1")
                .setNominalV(380)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        vl1.newLoad()
                .setId("load1")
                .setBus("b1")
                .setP0(50.0)
                .setQ0(50.0)
                .add();
        vl1.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(100)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();

        Substation s3 = network.newSubstation()
                .setId("S3")
                .add();
        VoltageLevel vl3 = s3.newVoltageLevel()
                .setId("vl3")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl3.getBusBreakerView().newBus()
                .setId("b3")
                .add();

        Substation s4 = network.newSubstation()
                .setId("S4")
                .add();
        VoltageLevel vl4 = s4.newVoltageLevel()
                .setId("vl4")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl4.getBusBreakerView().newBus()
                .setId("b4")
                .add();
        vl4.newLoad()
                .setId("load4")
                .setBus("b4")
                .setP0(40.0)
                .setQ0(40.0)
                .add();
        vl4.newGenerator()
                .setId("g4")
                .setConnectableBus("b4")
                .setBus("b4")
                .setTargetP(20)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();

        DanglingLine dl1 = vl1.newDanglingLine()
                .setBus("b1")
                .setId("h1")
                .setR(0.0)
                .setX(0.1)
                .setPairingKey("xnode")
                .setP0(0.0)
                .setQ0(0.0)
                .add();
        DanglingLine dl3 = vl3.newDanglingLine()
                .setBus("b3")
                .setId("h2")
                .setR(0.0)
                .setX(0.08)
                .setPairingKey("xnode")
                .setP0(0.0)
                .setQ0(0.0)
                .add();

        network.newTieLine()
                .setId("t12")
                .setDanglingLine1(dl1.getId())
                .setDanglingLine2(dl3.getId())
                .add();

        network.newLine()
                .setId("l34")
                .setBus1("b3")
                .setBus2("b4")
                .setR(0)
                .setX(1.0)
                .add();

        return network;
    }

    public static Network createWithTwoTieLines() {

        Network network = createWithTieLine();

        DanglingLine dl1 = network.getVoltageLevel("vl1").newDanglingLine()
                .setBus("b1")
                .setId("h1bis")
                .setR(0.0)
                .setX(0.1)
                .setPairingKey("xnode2")
                .setP0(0.0)
                .setQ0(0.0)
                .add();
        DanglingLine dl3 = network.getVoltageLevel("vl3").newDanglingLine()
                .setBus("b3")
                .setId("h2bis")
                .setR(0.0)
                .setX(0.08)
                .setPairingKey("xnode2")
                .setP0(0.0)
                .setQ0(0.0)
                .add();

        network.newTieLine()
                .setId("t12bis")
                .setDanglingLine1(dl1.getId())
                .setDanglingLine2(dl3.getId())
                .add();

        return network;
    }

    /**
     *   b1 --- b2
     *   |
     *   g1
     */
    public static Network createWithoutLoads() {
        Network network = Network.create("dl", "test");
        Substation s1 = network.newSubstation()
                .setId("S1")
                .add();
        Substation s2 = network.newSubstation()
                .setId("S2")
                .add();

        VoltageLevel vl1 = s1.newVoltageLevel()
                .setId("vl1")
                .setNominalV(225)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        var g1 = vl1.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(1E-6)
                .setTargetV(224.18)
                .setMinP(0)
                .setMaxP(245)
                .setVoltageRegulatorOn(true)
                .add();
        g1.newMinMaxReactiveLimits().setMinQ(-80).setMaxQ(86).add();

        VoltageLevel vl2 = s2.newVoltageLevel()
                .setId("vl2")
                .setNominalV(225)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        network.newLine()
                .setId("l1")
                .setBus1("b1")
                .setBus2("b2")
                .setR(1.316)
                .setX(6.865)
                .setB1(0.0017)
                .setB2(0.0017)
                .add();

        return network;
    }
}
