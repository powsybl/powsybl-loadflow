/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.*;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class MultiAreaNetworkFactory extends AbstractLoadFlowNetworkFactory {

    /**
     *    g1 100 MW
     *       |
     *      b1 ---(l12)--- b2
     *      |
     *  load1 60MW
     */
    public static Network create() {
        Network network = Network.create("areas", "test");
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
                .setTargetP(100)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();
        vl1.newLoad()
                .setId("load1")
                .setBus("b1")
                .setP0(60.0)
                .setQ0(10.0)
                .add();
        VoltageLevel vl2 = s2.newVoltageLevel()
                .setId("vl2")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        network.newLine()
                .setId("l12")
                .setBus1("b1")
                .setBus2("b2")
                .setR(0)
                .setX(1)
                .add();
        return network;
    }

    /**
     *   g1 100 MW
     *      |
     *      b1 ---(l12)--- b2 ---(l23)--- b3
     *      |                             |
     *  load1 60MW                     load3 10MW
     *   <---------------------->
     *          Area 1
     *
     */
    public static Network createOneAreaBase() {
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
                .setId("l23")
                .setBus1("b2")
                .setBus2("b3")
                .setR(0)
                .setX(1)
                .add();
        network.newArea()
                .setId("a1")
                .setName("Area 1")
                .setAreaType("ControlArea")
                .setInterchangeTarget(-10)
                .addVoltageLevel(network.getVoltageLevel("vl1"))
                .addVoltageLevel(network.getVoltageLevel("vl2"))
                .addAreaBoundary(network.getLine("l23").getTerminal2(), true)
                .add();
        return network;
    }

    /**
     *   g1 100 MW                                          gen3 40MW
     *      |                                                    |
     *      b1 ---(l12)--- b2                                    b3
     *      |                                                   |
     *    load1 60MW                                        load3 50MW
     *    <-------------------------------->    <------------------->
     *                Area 1                            Area 2
     */
    public static Network createTwoAreasBase() {
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
                .setP0(50.0)
                .setQ0(5.0)
                .add();
        vl3.newGenerator()
                .setId("gen3")
                .setBus("b3")
                .setTargetP(40)
                .setTargetQ(0)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();
        network.newArea()
                .setId("a1")
                .setName("Area 1")
                .setAreaType("ControlArea")
                .setInterchangeTarget(-50)
                .addVoltageLevel(network.getVoltageLevel("vl1"))
                .addVoltageLevel(network.getVoltageLevel("vl2"))
                .add();
        network.newArea()
                .setId("a2")
                .setName("Area 2")
                .setAreaType("ControlArea")
                .setInterchangeTarget(50)
                .addVoltageLevel(network.getVoltageLevel("vl3"))
                .add();
        return network;
    }

    /**
     *   g1 100 MW                                          gen3 40MW
     *      |                                                    |
     *      b1 ---(l12)--- b2 ---(l23_A1)--- bx1 ---(l23_A2)--- b3
     *      |                                                   |
     *    load1 60MW                                        load3 50MW
     *    <-------------------------------->    <------------------->
     *                Area 1                            Area 2
     */
    public static Network createTwoAreasWithXNode() {
        Network network = createTwoAreasBase();
        Bus bx1 = createBus(network, "bx1", 400);
        createLine(network, network.getBusBreakerView().getBus("b2"), bx1, "l23_A1", 1);
        createLine(network, bx1, network.getBusBreakerView().getBus("b3"), "l23_A2", 1);
        network.getArea("a1")
                .newAreaBoundary()
                .setTerminal(network.getLine("l23_A1").getTerminal2())
                .setAc(true)
                .add();
        network.getArea("a2")
                .newAreaBoundary()
                .setTerminal(network.getLine("l23_A2").getTerminal1())
                .setAc(true)
                .add();
        return network;
    }

    /**
     *   g1 100 MW                                          gen3 40MW
     *      |                                                    |
     *      b1 ---(l12)--- b2 ---(l23_A1)--- bx1 ---(l23_A2)---  b3 - load3 50MW
     *      |              |                                     |
     *   load1 60MW        |                                     |
     *                     + --(l23_A1_1)--- bx2 ---(l23_A2_1)-- +
     *    <-------------------------------->    <------------------->
     *                Area 1                            Area 2
     *    The second xnode is not considered in Areas' boundaries.
     */

    public static Network createTwoAreasWithTwoXNodes() {
        Network network = createTwoAreasWithXNode();
        Bus bx2 = createBus(network, "bx2", 400);
        createLine(network, network.getBusBreakerView().getBus("b2"), bx2, "l23_A1_1", 1);
        createLine(network, bx2, network.getBusBreakerView().getBus("b3"), "l23_A2_1", 1);
        return network;
    }

    /**
     *   g1 100 MW        dl1 30MW                             gen3 40MW
     *      |               |                                    |
     *      b1 ---(l12)--- b2 ---(l23_A1)--- bx1 ---(l23_A2)--- b3
     *      |                                                   |
     *    load1 60MW                                        load3 50MW
     *    <-------------------------------->    <------------------->
     *                Area 1                            Area 2
     */
    public static Network createTwoAreasWithDanglingLine() {
        Network network = createTwoAreasWithXNode();
        VoltageLevel vl2 = network.getVoltageLevel("vl2");
        vl2.newDanglingLine()
                .setId("dl1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setR(0)
                .setX(1)
                .setG(0)
                .setB(0)
                .setP0(20)
                .setQ0(20)
                .newGeneration()
                .setTargetP(0)
                .setTargetQ(0)
                .setTargetV(400)
                .setVoltageRegulationOn(false)
                .add()
                .add();
        Area a1 = network.getArea("a1");
        a1.newAreaBoundary()
                .setBoundary(network.getDanglingLine("dl1").getBoundary())
                .setAc(true)
                .add();
        return network;
    }

    /**
     *   g1 100 MW                                       gen3 40MW
     *      |                                                |
     *      b1 ---(l12)--- b2 ---(dlA1)----< >----(dlA2)--- b3
     *      |                                                |
     *    load1 60MW                                     load3 50MW
     *    <------------------------------->   <--------------------->
     *                Area 1                            Area 2
     */
    public static Network createTwoAreasWithTieLine() {
        Network network = createTwoAreasBase();
        VoltageLevel vl2 = network.getVoltageLevel("vl2");
        DanglingLine dl1 = vl2.newDanglingLine()
                .setId("dl1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setR(0)
                .setX(1)
                .setG(0)
                .setB(0)
                .setP0(0)
                .setQ0(0)
                .setPairingKey("tlA1A2")
                .add();
        VoltageLevel vl3 = network.getVoltageLevel("vl3");
        DanglingLine dl2 = vl3.newDanglingLine()
                .setId("dl2")
                .setConnectableBus("b3")
                .setBus("b3")
                .setR(0)
                .setX(1)
                .setG(0)
                .setB(0)
                .setP0(0)
                .setQ0(0)
                .setPairingKey("tlA1A2")
                .add();
        network.newTieLine()
                .setId("tl1")
                .setName("Tie Line A1-A2")
                .setDanglingLine1("dl1")
                .setDanglingLine2("dl2")
                .add();
        network.getArea("a1")
                .newAreaBoundary()
                .setBoundary(dl1.getBoundary())
                .setAc(true)
                .add();
        network.getArea("a2")
                .newAreaBoundary()
                .setBoundary(dl2.getBoundary())
                .setAc(true)
                .add();
        return network;
    }

    /**
     *   g1 100 MW                                       gen3 40MW
     *      |                                                |
     *      b1 ---(l12)--- b2 ---(dlA1)----< >----(dlA2)--- b3
     *      |              |                                |
     *    load1 60MW       |                           load3 50MW
     *                     |
     *                     + ---(dlA1_1)---< >---(dlA1_2)--- b4 -- gen4 5 MW
     *    <------------------------------->   <--------------------->
     *                Area 1                            Area 2
     *  The second tie line is not considered in Areas' boundaries.
     */
    public static Network createTwoAreasWithUnconsideredTieLine() {
        Network network = createTwoAreasWithTieLine();
        VoltageLevel vl2 = network.getVoltageLevel("vl2");
        vl2.newDanglingLine()
                .setId("dlA1_1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setR(0)
                .setX(1)
                .setG(0)
                .setB(0)
                .setP0(0)
                .setQ0(0)
                .setPairingKey("tlA1A2_2")
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
        vl4.newGenerator()
                .setId("gen4")
                .setConnectableBus("b4")
                .setBus("b4")
                .setTargetP(5)
                .setTargetQ(0)
                .setTargetV(400)
                .setMinP(0)
                .setMaxP(30)
                .setVoltageRegulatorOn(true)
                .add();
        vl4.newDanglingLine()
                .setId("dlA1_2")
                .setConnectableBus("b4")
                .setBus("b4")
                .setR(0)
                .setX(1)
                .setG(0)
                .setB(0)
                .setP0(0)
                .setQ0(0)
                .setPairingKey("tlA1A2_2")
                .add();
        network.newTieLine()
                .setId("tl2")
                .setName("Tie Line A1-A2 2")
                .setDanglingLine1("dlA1_1")
                .setDanglingLine2("dlA1_2")
                .add();
        network.getArea("a2")
                        .addVoltageLevel(vl4);
        network.newLine()
                .setId("l24")
                .setBus1("b2")
                .setBus2("b4")
                .setR(0)
                .setX(1)
                .add();
        return network;
    }

    /**
     * same as createTwoAreasWithTieLine but with a small dummy island and a2 has a boundary in it.
     */

    public static Network createAreaTwoComponents() {
        Network network = createTwoAreasWithTieLine();
        // create dummy bus in another island
        Bus dummy = createBus(network, "dummy");
        Bus dummy2 = createBus(network, "dummy2");
        createGenerator(dummy, "dummyGen", 1);
        createLoad(dummy2, "dummyLoad", 1.1);
        createLine(network, dummy, dummy2, "dummyLine", 0);
        network.getArea("a2").addVoltageLevel(dummy.getVoltageLevel()).addVoltageLevel(dummy2.getVoltageLevel());
        return network;
    }

    public static Network createAreaTwoComponentsWithBoundaries() {
        Network network = createAreaTwoComponents();
        Line dummyLine = network.getLine("dummyLine");
        network.getArea("a2").newAreaBoundary()
                .setTerminal(dummyLine.getTerminal1())
                .setAc(true)
                .add();
        return network;
    }

    public static Network createWithAreaWithoutBoundariesOrTarget() {
        Network network = createTwoAreasBase();
        // Area a1 has no boundaries

        // Area a2 has boundaries and an interchange target
        network.getArea("a2").newAreaBoundary()
                .setTerminal(network.getLine("l12").getTerminal2())
                .setAc(true)
                .add();
        createBus(network, "b4");
        createLine(network, network.getBusBreakerView().getBus("b2"), network.getBusBreakerView().getBus("b3"), "l23_A2", 1);
        createLine(network, network.getBusBreakerView().getBus("b3"), network.getBusBreakerView().getBus("b4"), "l34", 1);

        // Area a3 has boundaries but no target
        network.newArea()
                .setId("a3")
                .setName("Area 3")
                .setAreaType("ControlArea")
                .addVoltageLevel(network.getVoltageLevel("b4_vl"))
                .addAreaBoundary(network.getLine("l34").getTerminal2(), true)
                .add();

        // Area a4 has boundaries and a target but no voltage levels
        network.newArea()
                .setId("a4")
                .setName("Area 4")
                .setAreaType("ControlArea")
                .addAreaBoundary(network.getLine("l34").getTerminal2(), true)
                .add();
        return network;
    }

}
