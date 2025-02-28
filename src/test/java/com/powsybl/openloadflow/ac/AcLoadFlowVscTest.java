/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRangeAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.HvdcNetworkFactory;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static com.powsybl.openloadflow.util.LoadFlowAssert.assertActivePowerEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcLoadFlowVscTest {

    @Test
    void test() {
        Network network = HvdcNetworkFactory.createVsc();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        Bus bus1 = network.getBusView().getBus("vl1_0");
        assertVoltageEquals(390, bus1);
        assertAngleEquals(0, bus1);

        Bus bus2 = network.getBusView().getBus("vl2_0");
        assertVoltageEquals(385, bus2);
        assertAngleEquals(0.117616, bus2);

        Bus bus3 = network.getBusView().getBus("vl3_0");
        assertVoltageEquals(383, bus3);
        assertAngleEquals(0, bus3);

        Generator g1 = network.getGenerator("g1");
        assertActivePowerEquals(-102.56, g1.getTerminal());
        assertReactivePowerEquals(-615.918, g1.getTerminal());

        VscConverterStation cs2 = network.getVscConverterStation("cs2");
        assertActivePowerEquals(50.00, cs2.getTerminal());
        assertReactivePowerEquals(598.228, cs2.getTerminal());

        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(-49.35, cs3.getTerminal());
        assertReactivePowerEquals(-10.0, cs3.getTerminal());

        Line l12 = network.getLine("l12");
        assertActivePowerEquals(102.563, l12.getTerminal1());
        assertReactivePowerEquals(615.918, l12.getTerminal1());
        assertActivePowerEquals(-99.999, l12.getTerminal2());
        assertReactivePowerEquals(-608.228, l12.getTerminal2());
    }

    @Test
    void testRegulatingTerminal() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getGenerator("g1").setTargetQ(50).setVoltageRegulatorOn(false);
        VscConverterStation vscConverterStation = network.getVscConverterStation("cs2");
        vscConverterStation.setRegulatingTerminal(network.getGenerator("g1").getTerminal()).setVoltageSetpoint(390);
        vscConverterStation.setVoltageRegulatorOn(true); //FIXME

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters().setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        Bus bus1 = network.getBusView().getBus("vl1_0");
        assertVoltageEquals(390.0, bus1);
    }

    @Test
    void testRegulatingTerminal2() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getGenerator("g1").setTargetV(390);
        VscConverterStation vscConverterStation = network.getVscConverterStation("cs2");
        vscConverterStation.setRegulatingTerminal(network.getVscConverterStation("cs3").getTerminal()).setVoltageSetpoint(400); // will be discarded.
        vscConverterStation.setVoltageRegulatorOn(true); //FIXME

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        Bus bus1 = network.getBusView().getBus("vl1_0");
        assertVoltageEquals(390.0, bus1);
    }

    @Test
    void testHvdcAcEmulation() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getHvdcLine("hvdc23").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();
        network.newLine()
                .setId("l23")
                .setBus1("b2")
                .setBus2("b3")
                .setR(1)
                .setX(3)
                .add();

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters().setHvdcAcEmulation(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        VscConverterStation cs2 = network.getVscConverterStation("cs2");
        assertActivePowerEquals(-4.9634, cs2.getTerminal());
        assertReactivePowerEquals(360.034, cs2.getTerminal());

        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(5.0286, cs3.getTerminal());
        assertReactivePowerEquals(226.984, cs3.getTerminal());
    }

    @Test
    void testHvdcAcEmulation2() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(2)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD).setHvdcAcEmulation(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(-0.108, cs3.getTerminal());
        assertReactivePowerEquals(-4.226, cs3.getTerminal());

        VscConverterStation cs4 = network.getVscConverterStation("cs4");
        assertActivePowerEquals(0.1105, cs4.getTerminal());
        assertReactivePowerEquals(-3.600, cs4.getTerminal());

        network.getVscConverterStation("cs3").setVoltageRegulatorOn(false);
        network.getVscConverterStation("cs4").setVoltageRegulatorOn(false);
        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);
        assertTrue(result2.isFullyConverged());

        assertActivePowerEquals(-0.0853, cs3.getTerminal());
        assertReactivePowerEquals(0.0, cs3.getTerminal());
        assertActivePowerEquals(0.0872, cs4.getTerminal());
        assertReactivePowerEquals(0.0, cs4.getTerminal());
    }

    @Test
    void testHvdcAcEmulationNonSupported() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getHvdcLine("hvdc23").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters().setHvdcAcEmulation(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        VscConverterStation cs2 = network.getVscConverterStation("cs2");
        assertActivePowerEquals(50.0, cs2.getTerminal());
        assertReactivePowerEquals(598.227, cs2.getTerminal());

        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(-49.35, cs3.getTerminal());
        assertReactivePowerEquals(-10.0, cs3.getTerminal());
    }

    @Test
    void testHvdcDisconnectedAtOneSide() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getVscConverterStation("cs3").getTerminal().disconnect();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        Bus bus1 = network.getBusView().getBus("vl1_0");
        assertVoltageEquals(390, bus1);
        assertAngleEquals(0, bus1);

        Bus bus2 = network.getBusView().getBus("vl2_0");
        assertVoltageEquals(385, bus2);
        assertAngleEquals(0.18116, bus2);

        Bus bus3 = network.getBusView().getBus("vl3_0");
        assertVoltageEquals(Double.NaN, bus3);
        assertAngleEquals(Double.NaN, bus3);

        Generator g1 = network.getGenerator("g1");
        assertActivePowerEquals(-102.56, g1.getTerminal());
        assertReactivePowerEquals(-632.700, g1.getTerminal());

        VscConverterStation cs2 = network.getVscConverterStation("cs2");
        assertActivePowerEquals(0.00, cs2.getTerminal());
        assertReactivePowerEquals(614.750, cs2.getTerminal());

        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(Double.NaN, cs3.getTerminal());
        assertReactivePowerEquals(Double.NaN, cs3.getTerminal());

        Line l12 = network.getLine("l12");
        assertActivePowerEquals(52.65, l12.getTerminal1());
        assertReactivePowerEquals(632.700, l12.getTerminal1());
        assertActivePowerEquals(-50.00, l12.getTerminal2());
        assertReactivePowerEquals(-624.750, l12.getTerminal2());
    }

    @Test
    void testVscConverterWithoutHvdcLineNpe() {
        Network network = HvdcNetworkFactory.createVsc();
        network.getHvdcLine("hvdc23").remove();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network);
        assertTrue(result.isFullyConverged());
    }

    @Test
    void testHvdcPowerAcEmulation() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, new LoadFlowParameters());
        assertTrue(result.isFullyConverged());
        // AC Emulation takes into account cable loss
        assertActivePowerEquals(198.158, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(-193.799, network.getHvdcConverterStation("cs3").getTerminal());
        assertActivePowerEquals(-304.359, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.0, network.getLoad("l4").getTerminal());
    }

    @Test
    void testHvdcPowerAcEmulationWithoutR() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch();
        network.getHvdcLine("hvdc23").setR(0d); //Removing resistance to ignore cable loss
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, new LoadFlowParameters());
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(198.158, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(-193.822, network.getHvdcConverterStation("cs3").getTerminal());
        assertActivePowerEquals(-304.335, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.0, network.getLoad("l4").getTerminal());
    }

    @Test
    void testHvdcDirectionChangeAcEmulation() {
        Network network = HvdcNetworkFactory.createHvdcInAcEmulationInSymetricNetwork();
        network.getHvdcLine("hvdc12").setR(0.1d);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters().setHvdcAcEmulation(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        double pg2 = network.getGenerator("g2").getTerminal().getP();
        double pg1 = network.getGenerator("g1").getTerminal().getP();
        double pcs1 = network.getVscConverterStation("cs1").getTerminal().getP();
        double pcs2 = network.getVscConverterStation("cs2").getTerminal().getP();

        // Test basic energy conservation terms
        assertEquals(0.0, pg2, DELTA_POWER, "g2 should be off");
        assertTrue(-pg1 >= 5.99999, "g1 generates power for all loads");
        assertTrue(-pg1 <= 6.06, "reasonable loss");
        assertTrue(pcs1 > 0, "Power enters at cs1");
        assertTrue(pcs2 < 0, "Power delivered by cs2");
        assertTrue(Math.abs(pcs1) > Math.abs(pcs2), "Loss at HVDC output");

        // Test if removing line resistance increases the power transit
        network.getHvdcLine("hvdc12").setR(0d);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        double pcs1r0 = network.getVscConverterStation("cs1").getTerminal().getP();
        double pcs2r0 = network.getVscConverterStation("cs2").getTerminal().getP();
        assertTrue(pcs1r0 > 0, "Power enters at cs1");
        assertTrue(pcs2r0 < 0, "Power delivered by cs2");
        assertTrue(Math.abs(pcs2r0 + pcs1r0) < Math.abs(pcs2 - pcs1)); // Check that loss with R=0 is lower than loss with R!=0

        // Reverse power flow direction
        network.getHvdcLine("hvdc12").setR(0.1d);
        network.getGenerator("g2").setTargetP(5);
        network.getGenerator("g1").setTargetP(0);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        pg2 = network.getGenerator("g2").getTerminal().getP();
        pg1 = network.getGenerator("g1").getTerminal().getP();
        pcs1 = network.getVscConverterStation("cs1").getTerminal().getP();
        pcs2 = network.getVscConverterStation("cs2").getTerminal().getP();

        // Test basic energy conservation terms in symetric network
        // (active power is not a close enough symetric as in first run for some reason - so we can't compare b1 and b2 values for all termnals)
        assertEquals(0.0, pg1, DELTA_POWER, "g1 should be off");
        assertTrue(-pg2 >= 5.99999, "g2 generates power for all loads");
        assertTrue(-pg2 <= 6.06, "reasonable loss");
        assertTrue(pcs2 > 0, "Power enters at cs2");
        assertTrue(pcs1 < 0, "Power delivered by cs1");
        assertTrue(Math.abs(pcs2) > Math.abs(pcs1), "Loss at HVDC output");

        // Test if removing line resistance increases the power transit in symetric network
        network.getHvdcLine("hvdc12").setR(0d);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        pcs1r0 = network.getVscConverterStation("cs1").getTerminal().getP();
        pcs2r0 = network.getVscConverterStation("cs2").getTerminal().getP();
        assertTrue(pcs2r0 > 0, "Power enters at cs2");
        assertTrue(pcs1r0 < 0, "Power delivered by cs1");
        assertTrue(Math.abs(pcs2r0 + pcs1r0) < Math.abs(pcs2 - pcs1)); // Check that loss with R=0 is lower than loss with R!=0
    }

    @Test
    void testLccOpenAtOneSide() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.LCC);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network);
        assertTrue(result.isFullyConverged());

        // generator g1 expected to deliver enough power for the load
        assertActivePowerEquals(-304.400, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.00, network.getLoad("l4").getTerminal());
        assertActivePowerEquals(-195.600, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(200.00, network.getHvdcConverterStation("cs3").getTerminal());

        Line l34 = network.getLine("l34");
        l34.getTerminals().stream().forEach(Terminal::disconnect);
        result = loadFlowRunner.run(network);
        assertTrue(result.isFullyConverged()); // note that for LCC test the smaller component is flagged as NO_CALCULATION

        assertActivePowerEquals(-300.00, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.00, network.getLoad("l4").getTerminal());
        assertTrue(Double.isNaN(network.getHvdcConverterStation("cs2").getTerminal().getP())); // FIXME
        assertTrue(Double.isNaN(network.getHvdcConverterStation("cs3").getTerminal().getP()));
    }

    @Test
    void testVscOpenAtOneSide() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.VSC);
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setHvdcAcEmulation(false);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        // generator g1 expected to deliver enough power for the load
        assertActivePowerEquals(-304.400, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.00, network.getLoad("l4").getTerminal());
        assertActivePowerEquals(-195.600, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(200.00, network.getHvdcConverterStation("cs3").getTerminal());

        Line l34 = network.getLine("l34");
        l34.getTerminals().stream().forEach(Terminal::disconnect);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(-300.00, network.getGenerator("g1").getTerminal());
        assertActivePowerEquals(300.00, network.getLoad("l4").getTerminal());
        assertActivePowerEquals(0.0, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(0.0, network.getHvdcConverterStation("cs3").getTerminal());
    }

    @Test
    void testHvdcAndGenerator() {
        Network network = HvdcNetworkFactory.createWithHvdcAndGenerator();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, new LoadFlowParameters());
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-1.956, network.getVscConverterStation("cs3").getTerminal());
        assertActivePowerEquals(2.0, network.getVscConverterStation("cs4").getTerminal());
        assertActivePowerEquals(-2.0, network.getGenerator("g4").getTerminal());
        assertActivePowerEquals(-2.047, network.getGenerator("g1").getTerminal());
    }

    @Test
    void testVscVoltageControlWithZeroTargetP() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.VSC);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        // Set specific voltage setPoints to the stations
        double vcs2 = 397;
        double vcs3 = 401;
        network.getVscConverterStation("cs2").setVoltageSetpoint(vcs2);
        network.getVscConverterStation("cs3").setVoltageSetpoint(vcs3);

        // shut down active power flow in HVDC
        network.getHvdcLine("hvdc23").setActivePowerSetpoint(0);
        network.getHvdcLine("hvdc23").getExtension(HvdcAngleDroopActivePowerControl.class).setDroop(0).setP0(0);

        LoadFlowParameters p = new LoadFlowParameters();

        // without AC emulation
        p.setHvdcAcEmulation(false);
        LoadFlowResult result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(0, network.getVscConverterStation("cs2").getTerminal());
        assertVoltageEquals(vcs2, network.getVscConverterStation("cs2").getTerminal().getBusView().getBus());
        assertActivePowerEquals(0, network.getVscConverterStation("cs3").getTerminal());
        assertVoltageEquals(vcs3, network.getVscConverterStation("cs3").getTerminal().getBusView().getBus());

        // with AC emulation
        p.setHvdcAcEmulation(true);
        result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(0, network.getVscConverterStation("cs2").getTerminal());
        assertVoltageEquals(vcs2, network.getVscConverterStation("cs2").getTerminal().getBusView().getBus());
        assertActivePowerEquals(0, network.getVscConverterStation("cs3").getTerminal());
        assertVoltageEquals(vcs3, network.getVscConverterStation("cs3").getTerminal().getBusView().getBus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testVscVoltageControlWithOneSideDisconnected(boolean withFictiveLoad) {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.VSC);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        // Set specific voltage setPoints to the stations
        double vcs2 = 397;
        double vcs3 = 401;
        network.getVscConverterStation("cs2").setVoltageSetpoint(vcs2);
        network.getVscConverterStation("cs3").setVoltageSetpoint(vcs3);

        if (withFictiveLoad) {
            // Add a fictive load to the bus that will be disconnected
            network.getBusBreakerView().getBus("b3").getVoltageLevel().newLoad()
                    .setId("fictiveLoad")
                    .setBus("b3")
                    .setConnectableBus("b3")
                    .setP0(5)
                    .setQ0(2)
                    .setFictitious(true)
                    .add();
        }

        LoadFlowParameters p = new LoadFlowParameters();
        LoadFlowResult result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());
        // Just test that the HVDC is open - no need for more precision
        assertTrue(network.getVscConverterStation("cs2").getTerminal().getP() > 100);
        assertVoltageEquals(vcs2, network.getVscConverterStation("cs2").getTerminal().getBusView().getBus());

        // Disconnect line at HVDCoutput
        Line l34 = network.getLine("l34");
        l34.getTerminals().stream().forEach(Terminal::disconnect);

        // without AC emulation
        p.setHvdcAcEmulation(false);
        result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(0, network.getVscConverterStation("cs2").getTerminal());
        assertVoltageEquals(vcs2, network.getVscConverterStation("cs2").getTerminal().getBusView().getBus());

        // with AC emulation
        p.setHvdcAcEmulation(true);
        result = loadFlowRunner.run(network, p);

        assertActivePowerEquals(0, network.getVscConverterStation("cs2").getTerminal());
        assertVoltageEquals(vcs2, network.getVscConverterStation("cs2").getTerminal().getBusView().getBus());
    }

    @Test
    void testAcEmuWithOperationalLimits() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.VSC, 80);
        // without limit p=195
        network.getHvdcLine("hvdc23")
                .newExtension(HvdcOperatorActivePowerRangeAdder.class)
                .withOprFromCS2toCS1(180)
                .withOprFromCS1toCS2(170)
                .add();

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters p = new LoadFlowParameters();
        p.setHvdcAcEmulation(true);
        LoadFlowResult result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());

        // Active flow capped at limit. Output has losses (due to VSC stations)
        assertEquals(170, network.getHvdcConverterStation("cs2").getTerminal().getP(), DELTA_POWER);
        assertEquals(-166.263, network.getHvdcConverterStation("cs3").getTerminal().getP(), DELTA_POWER);

        // now invert power direction
        HvdcAngleDroopActivePowerControl activePowerControl = network.getHvdcLine("hvdc23").getExtension(HvdcAngleDroopActivePowerControl.class);
        activePowerControl.setP0(-activePowerControl.getP0());
        result = loadFlowRunner.run(network, p);
        assertTrue(result.isFullyConverged());

        // Active flow capped at other direction's limit. Output has losses (due to VSC stations)
        assertEquals(-176.042, network.getHvdcConverterStation("cs2").getTerminal().getP(), DELTA_POWER);
        assertEquals(180, network.getHvdcConverterStation("cs3").getTerminal().getP(), DELTA_POWER);
    }

    @Test
    void testAcEmuAndPMax() {
        Network network = HvdcNetworkFactory.createHvdcLinkedByTwoLinesAndSwitch(HvdcConverterStation.HvdcType.VSC, 80);
        // without limit p=195
        network.getHvdcLine("hvdc23")
                .setMaxP(170);

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters p = new LoadFlowParameters();
        p.setHvdcAcEmulation(true);
        LoadFlowResult result = loadFlowRunner.run(network, p);

        assertTrue(result.isFullyConverged());

        // Active flow capped at limit. Output has losses (due to VSC stations)
        assertActivePowerEquals(170, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(-166.263, network.getHvdcConverterStation("cs3").getTerminal());

        // now invert power direction
        HvdcAngleDroopActivePowerControl activePowerControl = network.getHvdcLine("hvdc23").getExtension(HvdcAngleDroopActivePowerControl.class);
        activePowerControl.setP0(-activePowerControl.getP0());
        result = loadFlowRunner.run(network, p);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(-166.263, network.getHvdcConverterStation("cs2").getTerminal());
        assertActivePowerEquals(170, network.getHvdcConverterStation("cs3").getTerminal());
    }

    @Test
    void testDcLoadFlowWithHvdcAcEmulation2() {
        Network network = HvdcNetworkFactory.createVsc();
        network.newLine() // in order to have only one synchronous component for the moment.
                .setId("l23")
                .setVoltageLevel1("vl2")
                .setBus1("b2")
                .setVoltageLevel2("vl3")
                .setBus2("b3")
                .setR(1)
                .setX(3)
                .setG1(0)
                .setG2(0)
                .setB1(0)
                .setB2(0)
                .add();
        HvdcAngleDroopActivePowerControl hvdcAngleDroopActivePowerControl = network.getHvdcLine("hvdc23").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters()
                .setDc(true);
        OpenLoadFlowParameters olfParams = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        VscConverterStation cs2 = network.getVscConverterStation("cs2");
        assertActivePowerEquals(8.102, cs2.getTerminal()); // 0MW + 180 MW/deg * 0.04501deg
        assertAngleEquals(0.0, cs2.getTerminal().getBusView().getBus());
        VscConverterStation cs3 = network.getVscConverterStation("cs3");
        assertActivePowerEquals(-8.102, cs3.getTerminal());
        assertAngleEquals(-0.04501, cs3.getTerminal().getBusView().getBus());

        // Now with a non null P0
        hvdcAngleDroopActivePowerControl.setP0(10);

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(16.482, cs2.getTerminal()); // 10MW + 180 MW/deg * 0.036008deg
        assertAngleEquals(0.0, cs2.getTerminal().getBusView().getBus());
        assertActivePowerEquals(-16.482, cs3.getTerminal());
        assertAngleEquals(-0.036008, cs3.getTerminal().getBusView().getBus());

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(16.482, cs2.getTerminal()); // 10MW + 180 MW/deg * 0.036008deg
        assertAngleEquals(0.0, cs2.getTerminal().getBusView().getBus());
        assertActivePowerEquals(-16.482, cs3.getTerminal());
        assertAngleEquals(-0.036008, cs3.getTerminal().getBusView().getBus());

        // Add another HVDC line connected the other way arround to make sure all equation terms are run
        // (the equations on the slack bus are not run)
        network.getVoltageLevel("vl2")
                .newVscConverterStation()
                .setId("cs2Bis")
                .setConnectableBus("b2")
                .setBus("b2")
                .setVoltageRegulatorOn(true)
                .setVoltageSetpoint(385)
                .setReactivePowerSetpoint(100)
                .setLossFactor(1.1f)
                .add();
        network.getVoltageLevel("vl3")
                .newVscConverterStation()
                .setId("cs3Bis")
                .setConnectableBus("b3")
                .setBus("b3")
                .setVoltageRegulatorOn(true)
                .setVoltageSetpoint(383)
                .setReactivePowerSetpoint(100)
                .setLossFactor(0.2f)
                .add();

        network.newHvdcLine()
                .setId("hvdc32")
                .setConverterStationId1("cs3Bis")
                .setConverterStationId2("cs2Bis")
                .setNominalV(400)
                .setR(0.1)
                .setActivePowerSetpoint(50)
                .setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                .setMaxP(500)
                .add();
        network.getHvdcLine("hvdc32").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(15.578, cs2.getTerminal()); // 10MW + 180 MW/deg * 0.030988deg
        assertAngleEquals(0.0, cs2.getTerminal().getBusView().getBus());
        assertActivePowerEquals(-15.578, cs3.getTerminal());
        assertAngleEquals(-0.030988, cs3.getTerminal().getBusView().getBus());
        assertActivePowerEquals(5.578, network.getVscConverterStation("cs2Bis").getTerminal()); // 0MW + 180 MW/deg * 0.030988deg
    }
}
