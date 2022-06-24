/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shunt test case.
 *
 * g1        ld1
 * |          |
 * b1---------b2
 *      l1    |
 *           shunt
 *
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
class AcLoadFlowShuntTest {

    private Network network;
    private Bus bus1;
    private Bus bus2;
    private Bus bus3;
    private Line l1;
    private Line l2;
    private ShuntCompensator shunt;

    private LoadFlow.Runner loadFlowRunner;

    private LoadFlowParameters parameters;

    private Network createNetwork() {
        Network network = Network.create("svc", "test");
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
        bus1 = vl1.getBusBreakerView().newBus()
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
        bus2 = vl2.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        vl2.newLoad()
                .setId("ld1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setP0(101)
                .setQ0(150)
                .add();
        VoltageLevel vl3 = s2.newVoltageLevel()
                .setId("vl3")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        bus3 = vl3.getBusBreakerView().newBus()
                .setId("b3")
                .add();
        shunt = vl3.newShuntCompensator()
                .setId("SHUNT")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(0)
                .setVoltageRegulatorOn(true)
                .setTargetV(393)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-3)
                .setG(0.0)
                .endSection()
                .beginSection()
                .setB(3e-3)
                .setG(0.)
                .endSection()
                .add()
                .add();
        l1 = network.newLine()
                .setId("l1")
                .setVoltageLevel1("vl1")
                .setBus1("b1")
                .setVoltageLevel2("vl2")
                .setBus2("b2")
                .setR(1)
                .setX(3)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        l2 = network.newLine()
                .setId("l2")
                .setVoltageLevel1("vl3")
                .setBus1("b3")
                .setVoltageLevel2("vl2")
                .setBus2("b2")
                .setR(1)
                .setX(3)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        return network;
    }

    @BeforeEach
    void setUp() {
        network = createNetwork();
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters().setNoGeneratorReactiveLimits(false)
                .setDistributedSlack(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
    }

    @Test
    void testBaseCase() {
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(390, bus1);
        assertVoltageEquals(388.581, bus2);
        assertVoltageEquals(388.581, bus3);
    }

    @Test
    void testShuntSectionOne() {
        shunt.setSectionCount(1);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(390, bus1);
        assertVoltageEquals(389.758, bus2);
        assertVoltageEquals(390.93051, bus3);
    }

    @Test
    void testShuntSectionTwo() {
        shunt.setSectionCount(2);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(390, bus1);
        assertVoltageEquals(392.149468, bus2);
        assertVoltageEquals(395.709, bus3);
    }

    @Test
    void testVoltageControl() {
        parameters.setShuntCompensatorVoltageControlOn(true);
        shunt.setSectionCount(0);
        shunt.setVoltageRegulatorOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(390.930, bus3);
        assertEquals(1, shunt.getSectionCount());
    }

    @Test
    void testRemoteVoltageControl() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        ShuntCompensator shuntCompensator2 = network.getShuntCompensator("SHUNT2");
        shuntCompensator2.setVoltageRegulatorOn(false);
        ShuntCompensator shuntCompensator3 = network.getShuntCompensator("SHUNT3");
        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(399.602, network.getBusBreakerView().getBus("b4"));
        assertEquals(0, shuntCompensator2.getSectionCount());
        assertEquals(27, shuntCompensator3.getSectionCount());
    }

    @Test
    void testLocalSharedVoltageControl() {
        network = createNetwork();
        shunt.setSectionCount(2);
        ShuntCompensator shunt2 = network.getVoltageLevel("vl3").newShuntCompensator()
                .setId("SHUNT2")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(0)
                .setVoltageRegulatorOn(true)
                .setRegulatingTerminal(l2.getTerminal1())
                .setTargetV(393)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-3)
                .setG(0.0)
                .endSection()
                .beginSection()
                .setB(3e-3)
                .setG(0.)
                .endSection()
                .add()
                .add();

        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(393.308, bus3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt2.getSectionCount());
    }

    @Test
    void testLocalSharedVoltageControl2() {
        network = createNetwork();
        // in that test case, we test two shunts connected to the same bus, both are in voltage regulation
        // we decrease the b per section of shunt2
        ShuntCompensator shunt2 = network.getVoltageLevel("vl3").newShuntCompensator()
                .setId("SHUNT2")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(0)
                .setVoltageRegulatorOn(true)
                .setRegulatingTerminal(l2.getTerminal1())
                .setTargetV(393)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-4)
                .setG(0.0)
                .endSection()
                .beginSection()
                .setB(3e-4)
                .setG(0.)
                .endSection()
                .add()
                .add();

        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(391.640, bus3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(2, shunt2.getSectionCount());
    }

    @Test
    void testLocalVoltageControl2() {
        network = createNetwork();
        // in that test case, we test two shunts connected to the same bus, but with just one in voltage regulation
        shunt.setSectionCount(2);
        ShuntCompensator shunt2 = network.getVoltageLevel("vl3").newShuntCompensator()
                .setId("SHUNT2")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(1)
                .setVoltageRegulatorOn(false)
                .setRegulatingTerminal(l2.getTerminal1())
                .setTargetV(393)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-3)
                .setG(0.0)
                .endSection()
                .beginSection()
                .setB(3e-3)
                .setG(0.)
                .endSection()
                .add()
                .add();

        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(393.308, bus3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt2.getSectionCount());
    }

    @Test
    void testLocalVoltageControl3() {
        network = createNetwork();
        // in that test case, we test two shunts connected to the same bus, but with just one in voltage regulation
        network.getShuntCompensator("SHUNT").remove();
        ShuntCompensator shunt2 = network.getVoltageLevel("vl3").newShuntCompensator()
                .setId("SHUNT2")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(10)
                .setVoltageRegulatorOn(true)
                .setRegulatingTerminal(l2.getTerminal1())
                .setTargetV(400)
                .setTargetDeadband(5.0)
                .newLinearModel()
                .setMaximumSectionCount(10)
                .setBPerSection(1E-3)
                .setGPerSection(0.0)
                .add()
                .add();

        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(400.600, bus3);
        assertEquals(5, shunt2.getSectionCount());
    }

    @Test
    void testSharedRemoteVoltageControl() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        parameters.setShuntCompensatorVoltageControlOn(true);
        ShuntCompensator shuntCompensator2 = network.getShuntCompensator("SHUNT2");
        ShuntCompensator shuntCompensator3 = network.getShuntCompensator("SHUNT3");
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(399.819, network.getBusBreakerView().getBus("b4"));
        assertEquals(13, shuntCompensator2.getSectionCount());
        assertEquals(13, shuntCompensator3.getSectionCount());
    }

    @Test
    void testNoShuntVoltageControl() {
        parameters.setShuntCompensatorVoltageControlOn(true);
        shunt.setRegulatingTerminal(network.getGenerator("g1").getTerminal());
        shunt.setSectionCount(0);
        shunt.setVoltageRegulatorOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(388.581, bus3);
        assertEquals(0, shunt.getSectionCount());
    }

    @Test
    void testNoShuntVoltageControl2() {
        parameters.setShuntCompensatorVoltageControlOn(true);
        shunt.setSectionCount(0);
        shunt.setVoltageRegulatorOn(true);
        shunt.setRegulatingTerminal(network.getLoad("ld1").getTerminal());
        network.getLoad("ld1").getTerminal().disconnect();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(389.999, bus3);
        assertEquals(0, shunt.getSectionCount());
    }

    @Test
    void testNoShuntVoltageControl3() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer("tr1");
        twt.newRatioTapChanger()
                .setTargetDeadband(0)
                .setTapPosition(0)
                .setLoadTapChangingCapabilities(true)
                .setRegulating(true)
                .setTargetV(400)
                .setRegulationTerminal(network.getLoad("l4").getTerminal())
                .beginStep()
                .setRho(0.9)
                .setR(0.1089)
                .setX(0.01089)
                .setG(0.8264462809917356)
                .setB(0.08264462809917356)
                .endStep()
                .beginStep()
                .setRho(1.0)
                .setR(0.121)
                .setX(0.0121)
                .setG(0.8264462809917356)
                .setB(0.08264462809917356)
                .endStep()
                .beginStep()
                .setRho(1.1)
                .setR(0.1331)
                .setX(0.01331)
                .setG(0.9090909090909092)
                .setB(0.09090909090909092)
                .endStep()
                .add();
        parameters.setShuntCompensatorVoltageControlOn(true);
        parameters.setTransformerVoltageControlOn(true);
        ShuntCompensator shuntCompensator2 = network.getShuntCompensator("SHUNT2");
        ShuntCompensator shuntCompensator3 = network.getShuntCompensator("SHUNT3");
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(407.978, network.getBusBreakerView().getBus("b4"));
        assertEquals(0, shuntCompensator2.getSectionCount());
        assertEquals(0, shuntCompensator3.getSectionCount());
    }

    @Test
    void testUnsupportedSharedVoltageControl() {
        network = createNetwork();
        // in that test case, we test two shunts connected to the same bus, both are in voltage regulation
        // but with a different regulating terminal.
        ShuntCompensator shunt2 = network.getVoltageLevel("vl3").newShuntCompensator()
                .setId("SHUNT2")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(0)
                .setVoltageRegulatorOn(true)
                .setRegulatingTerminal(l2.getTerminal2())
                .setTargetV(405)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-4)
                .setG(0.0)
                .endSection()
                .beginSection()
                .setB(3e-4)
                .setG(0.)
                .endSection()
                .add()
                .add();

        parameters.setShuntCompensatorVoltageControlOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertVoltageEquals(391.640, bus3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(2, shunt2.getSectionCount());
    }

    @Test
    void testGComponent() {
        // Reference test without G component on shunt
        shunt.setSectionCount(1);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertActivePowerEquals(101.366, l1.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(-101.299, l1.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(-2.166, l1.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(2.368, l1.getTerminal(Branch.Side.TWO));
        assertActivePowerEquals(0, l2.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(0.153, l2.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(152.826, l2.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(-152.368, l2.getTerminal(Branch.Side.TWO));
        //assertActivePowerEquals(0, shunt.getTerminal());
        assertReactivePowerEquals(-152.826, shunt.getTerminal());

        // Test with G component on shunt
        Network networkWithG = Network.create("svc", "testG");
        Substation s1G = networkWithG.newSubstation()
                .setId("S1")
                .add();
        Substation s2G = networkWithG.newSubstation()
                .setId("S2")
                .add();
        VoltageLevel vl1G = s1G.newVoltageLevel()
                .setId("vl1")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1G.getBusBreakerView().newBus()
                .setId("b1")
                .add();
        vl1G.newGenerator()
                .setId("g1")
                .setConnectableBus("b1")
                .setBus("b1")
                .setTargetP(101.3664)
                .setTargetV(390)
                .setMinP(0)
                .setMaxP(150)
                .setVoltageRegulatorOn(true)
                .add();
        VoltageLevel vl2G = s2G.newVoltageLevel()
                .setId("vl2")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2G.getBusBreakerView().newBus()
                .setId("b2")
                .add();
        vl2G.newLoad()
                .setId("ld1")
                .setConnectableBus("b2")
                .setBus("b2")
                .setP0(101)
                .setQ0(150)
                .add();
        VoltageLevel vl3G = s2G.newVoltageLevel()
                .setId("vl3")
                .setNominalV(400)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl3G.getBusBreakerView().newBus()
                .setId("b3")
                .add();
        ShuntCompensator shuntG = vl3G.newShuntCompensator()
                .setId("SHUNT")
                .setBus("b3")
                .setConnectableBus("b3")
                .setSectionCount(0)
                .setVoltageRegulatorOn(true)
                .setTargetV(393)
                .setTargetDeadband(5.0)
                .newNonLinearModel()
                .beginSection()
                .setB(1e-3)
                .setG(1e-3)
                .endSection()
                .beginSection()
                .setB(3e-3)
                .setG(3e-3)
                .endSection()
                .add()
                .add();
        Line l1G = networkWithG.newLine()
                .setId("l1")
                .setVoltageLevel1("vl1")
                .setBus1("b1")
                .setVoltageLevel2("vl2")
                .setBus2("b2")
                .setR(1)
                .setX(3)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        Line l2G = networkWithG.newLine()
                .setId("l2")
                .setVoltageLevel1("vl3")
                .setBus1("b3")
                .setVoltageLevel2("vl2")
                .setBus2("b2")
                .setR(1)
                .setX(3)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        shuntG.setSectionCount(1);
        LoadFlowResult result2 = loadFlowRunner.run(networkWithG, parameters);
        assertTrue(result2.isOk());
        assertActivePowerEquals(101.366, l1G.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(-101.299, l1G.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(-1.398, l1G.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(1.600, l1G.getTerminal(Branch.Side.TWO));
        assertActivePowerEquals(-152.513, l2G.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(152.818, l2G.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(152.514, l2G.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(-151.599, l2G.getTerminal(Branch.Side.TWO));
        //assertActivePowerEquals(152.513, shuntG.getTerminal());
        assertReactivePowerEquals(-152.514, shuntG.getTerminal());

        // Test with two sections
        shuntG.setSectionCount(2);
        LoadFlowResult result3 = loadFlowRunner.run(networkWithG, parameters);
        assertTrue(result3.isOk());
        assertActivePowerEquals(101.366, l1G.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(-100.682, l1G.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(-306.296, l1G.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(308.349, l1G.getTerminal(Branch.Side.TWO));
        assertActivePowerEquals(-466.739, l2G.getTerminal(Branch.Side.ONE));
        assertActivePowerEquals(469.540, l2G.getTerminal(Branch.Side.TWO));
        assertReactivePowerEquals(466.746, l2G.getTerminal(Branch.Side.ONE));
        assertReactivePowerEquals(-458.344, l2G.getTerminal(Branch.Side.TWO));
        //assertActivePowerEquals(466.739, shuntG.getTerminal());
        assertReactivePowerEquals(-466.743, shuntG.getTerminal());
    }
}
