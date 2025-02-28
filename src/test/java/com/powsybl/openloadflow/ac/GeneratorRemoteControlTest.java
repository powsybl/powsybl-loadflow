/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.CoordinatedReactiveControlAdder;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControlAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteriaType;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class GeneratorRemoteControlTest extends AbstractLoadFlowNetworkFactory {

    private Network network;
    Substation s;
    private Bus b1;
    private Bus b2;
    private Bus b3;
    private Bus b4;
    private Generator g1;
    private Generator g2;
    private Generator g3;
    private TwoWindingsTransformer tr1;
    private TwoWindingsTransformer tr2;
    private TwoWindingsTransformer tr3;
    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;
    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        network = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        s = network.getSubstation("s");
        b1 = network.getBusBreakerView().getBus("b1");
        b2 = network.getBusBreakerView().getBus("b2");
        b3 = network.getBusBreakerView().getBus("b3");
        b4 = network.getBusBreakerView().getBus("b4");
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        tr1 = network.getTwoWindingsTransformer("tr1");
        tr2 = network.getTwoWindingsTransformer("tr2");
        tr3 = network.getTwoWindingsTransformer("tr3");

        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters().setUseReactiveLimits(false)
                  .setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED)
                .setVoltageRemoteControl(true);
    }

    @Test
    void testWith3Generators() {
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.506559, b1);
        assertVoltageEquals(21.293879, b2);
        assertVoltageEquals(22.641227, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-69.925, g1.getTerminal());
        assertReactivePowerEquals(-69.925, g2.getTerminal());
        assertReactivePowerEquals(-69.925, g3.getTerminal());
    }

    @Test
    void testWith3GeneratorsAndNonImpedantBranch() {
        // add a non impedant branch going to a load at generator 3 connection bus.
        VoltageLevel vl5 = s.newVoltageLevel()
                .setId("vl5")
                .setNominalV(20)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl5.getBusBreakerView().newBus()
                .setId("b5")
                .add();
        vl5.newLoad()
                .setId("ld5")
                .setConnectableBus("b5")
                .setBus("b5")
                .setP0(0)
                .setQ0(30)
                .add();
        network.newLine()
                .setId("ln1")
                .setConnectableBus1("b3")
                .setBus1("b3")
                .setConnectableBus2("b5")
                .setBus2("b5")
                .setR(0)
                .setX(0)
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-79.892, g1.getTerminal());
        assertReactivePowerEquals(-79.892, g2.getTerminal());
        assertReactivePowerEquals(-79.892, g3.getTerminal());
    }

    @Test
    void testWithLoadConnectedToGeneratorBus() {
        // in that case we expect the generation reactive power to be equals for each of the controller buses
        b1.getVoltageLevel().newLoad()
                .setId("l")
                .setBus(b1.getId())
                .setConnectableBus(b1.getId())
                .setP0(0)
                .setQ0(10)
                .add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-73.283, g1.getTerminal());
        assertReactivePowerEquals(-73.283, g2.getTerminal());
        assertReactivePowerEquals(-73.283, g3.getTerminal());
        assertReactivePowerEquals(63.283, tr1.getTerminal1());
        assertReactivePowerEquals(73.283, tr2.getTerminal1());
        assertReactivePowerEquals(73.283, tr3.getTerminal1());

        // same test but with a more complex distribution
        g1.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(60).add();
        g2.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(30).add();
        g3.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(10).add();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-132.094, g1.getTerminal());
        assertReactivePowerEquals(-66.047, g2.getTerminal());
        assertReactivePowerEquals(-22.015, g3.getTerminal());
        assertReactivePowerEquals(122.094, tr1.getTerminal1());
        assertReactivePowerEquals(66.047, tr2.getTerminal1());
        assertReactivePowerEquals(22.015, tr3.getTerminal1());
    }

    @Test
    void testWithShuntConnectedToGeneratorBus() {
        // in that case we expect the generation reactive power to be equals for each of the controller buses
        b1.getVoltageLevel().newShuntCompensator()
                .setId("l")
                .setBus(b1.getId())
                .setConnectableBus(b1.getId())
                .setSectionCount(1)
                .newLinearModel()
                    .setBPerSection(Math.pow(10, -2))
                    .setMaximumSectionCount(1)
                    .add()
                .add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-68.372, g1.getTerminal());
        assertReactivePowerEquals(-68.372, g2.getTerminal());
        assertReactivePowerEquals(-68.372, g3.getTerminal());
        assertReactivePowerEquals(73.003, tr1.getTerminal1());
        assertReactivePowerEquals(68.372, tr2.getTerminal1());
        assertReactivePowerEquals(68.372, tr3.getTerminal1());
    }

    @Test
    void testWith3GeneratorsAndCoordinatedReactiveControlExtensions() {
        g1.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(60).add();
        g2.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(30).add();
        g3.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(10).add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.709276, b1);
        assertVoltageEquals(21.264396, b2);
        assertVoltageEquals(22.331965, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-126.14, g1.getTerminal());
        assertReactivePowerEquals(-63.07, g2.getTerminal());
        assertReactivePowerEquals(-21.023, g3.getTerminal());

        parametersExt.setRemoteVoltageControlIgnoreQPercent(true);
        g1.newMinMaxReactiveLimits().setMaxQ(100).setMinQ(-100).add();
        g2.newMinMaxReactiveLimits().setMaxQ(100).setMinQ(-100).add();
        g3.newMinMaxReactiveLimits().setMaxQ(200).setMinQ(-200).add();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.443, b1);
        assertVoltageEquals(21.219, b2);
        assertVoltageEquals(22.860, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-52.660, g1.getTerminal());
        assertReactivePowerEquals(-52.660, g2.getTerminal());  // same as g1
        assertReactivePowerEquals(-105.320, g3.getTerminal());  // twice g1

    }

    @Test
    void testWith3GeneratorsAndReactiveLimits() {
        // as there is no CoordinatedReactiveControl extension, reactive limit range will be used to create reactive
        // keys
        g1.newMinMaxReactiveLimits().setMinQ(0).setMaxQ(60).add();
        g2.newMinMaxReactiveLimits().setMinQ(0).setMaxQ(30).add();
        g3.newMinMaxReactiveLimits().setMinQ(0).setMaxQ(10).add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.709276, b1);
        assertVoltageEquals(21.264396, b2);
        assertVoltageEquals(22.331965, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-126.14, g1.getTerminal());
        assertReactivePowerEquals(-63.07, g2.getTerminal());
        assertReactivePowerEquals(-21.023, g3.getTerminal());
    }

    @Test
    void testErrorWhenDifferentTargetV() {
        g3.setTargetV(413.3);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(413.4, b4); // check target voltage has been fixed to first controller one
    }

    @Test
    void testWith2Generators() {
        g3.setTargetQ(10).setVoltageRegulatorOn(false);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.616159, b1);
        assertVoltageEquals(21.423099, b2);
        assertVoltageEquals(22.261066, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-100.189, g1.getTerminal());
        assertReactivePowerEquals(-100.189, g2.getTerminal());
        assertReactivePowerEquals(-10, g3.getTerminal());
    }

    @Test
    void testWith3GeneratorsAndFirstGeneratorToLimit() {
        parameters.setUseReactiveLimits(true);
        g1.newMinMaxReactiveLimits()
                .setMinQ(-50)
                .setMaxQ(50)
                .add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(21.433794, b1);
        assertVoltageEquals(21.337233, b2);
        assertVoltageEquals(22.704157, b3);
        assertVoltageEquals(413.4, b4);
        assertReactivePowerEquals(-50, g1.getTerminal()); // generator 1 has been correctly limited to -50 MVar
        assertReactivePowerEquals(-80.038, g2.getTerminal());
        assertReactivePowerEquals(-80.038, g3.getTerminal());
    }

    @Test
    void testWith3GeneratorsAndAnAdditionalWithLocalRegulation() {
        // create a generator on controlled bus to have "mixed" 1 local plus 3 remote generators controlling voltage
        // at bus 4
        Generator g4 = b4.getVoltageLevel()
                .newGenerator()
                .setId("g4")
                .setBus("b4")
                .setConnectableBus("b4")
                .setEnergySource(EnergySource.THERMAL)
                .setMinP(0)
                .setMaxP(200)
                .setTargetP(100)
                .setTargetV(413.4)
                .setVoltageRegulatorOn(true)
                .add();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-52.103, g1.getTerminal());
        assertReactivePowerEquals(-52.103, g2.getTerminal());
        assertReactivePowerEquals(-52.103, g3.getTerminal());
        assertReactivePowerEquals(-52.103, g4.getTerminal()); // local generator has the same reactive power that remote ones

        // check that distribution is correct even with 2 generators connected to local bus
        Generator g4bis = b4.getVoltageLevel()
                .newGenerator()
                .setId("g4bis")
                .setBus("b4")
                .setConnectableBus("b4")
                .setEnergySource(EnergySource.THERMAL)
                .setMinP(0)
                .setMaxP(200)
                .setTargetP(100)
                .setTargetV(413.4)
                .setVoltageRegulatorOn(true)
                .add();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-41.559, g1.getTerminal());
        assertReactivePowerEquals(-41.559, g2.getTerminal());
        assertReactivePowerEquals(-41.559, g3.getTerminal());
        assertReactivePowerEquals(-41.559, g4.getTerminal());
        assertReactivePowerEquals(-41.559, g4bis.getTerminal());

        // check that of we switch g4 PQ with Q=+-10MVar, generators that still regulate voltage already have a correct
        // amount of reactive power
        g4.setTargetQ(-10)
                .setVoltageRegulatorOn(false);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-54.646, g1.getTerminal());
        assertReactivePowerEquals(-54.646, g2.getTerminal());
        assertReactivePowerEquals(-54.646, g3.getTerminal());
        assertReactivePowerEquals(10, g4.getTerminal());
        assertReactivePowerEquals(-54.646, g4bis.getTerminal());

        g4.setTargetQ(10);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-49.563, g1.getTerminal());
        assertReactivePowerEquals(-49.563, g2.getTerminal());
        assertReactivePowerEquals(-49.563, g3.getTerminal());
        assertReactivePowerEquals(-10, g4.getTerminal());
        assertReactivePowerEquals(-49.563, g4bis.getTerminal());

        // same test but with one of the remote generator switched PQ
        g4.setVoltageRegulatorOn(true);
        g2.setTargetQ(-10)
                .setVoltageRegulatorOn(false);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-54.51, g1.getTerminal());
        assertReactivePowerEquals(10, g2.getTerminal());
        assertReactivePowerEquals(-54.51, g3.getTerminal());
        assertReactivePowerEquals(-54.51, g4.getTerminal());
        assertReactivePowerEquals(-54.51, g4bis.getTerminal());

        g2.setTargetQ(10);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-49.449, g1.getTerminal());
        assertReactivePowerEquals(-10, g2.getTerminal());
        assertReactivePowerEquals(-49.449, g3.getTerminal());
        assertReactivePowerEquals(-49.449, g4.getTerminal());
        assertReactivePowerEquals(-49.449, g4bis.getTerminal());

        // try to switch off regulation of the 3 remote generators
        g1.setTargetQ(10).setVoltageRegulatorOn(false);
        g2.setTargetQ(10).setVoltageRegulatorOn(false);
        g3.setTargetQ(10).setVoltageRegulatorOn(false);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-10, g1.getTerminal());
        assertReactivePowerEquals(-10, g2.getTerminal());
        assertReactivePowerEquals(-10, g3.getTerminal());
        assertReactivePowerEquals(-88.407, g4.getTerminal());
        assertReactivePowerEquals(-88.407, g4bis.getTerminal());
    }

    @Test
    void testGeneratorRemoteReactivePowerControl() {
        // create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");
        Line l12 = network.getLine("l12");

        double targetQ = 1.0;

        // disable voltage control on g4
        g4.setTargetQ(0).setVoltageRegulatorOn(false);

        // first test: generator g4 regulates reactive power on line 4->3 (on side of g4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
          .withTargetQ(targetQ)
          .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
          .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, l34.getTerminal(TwoSides.TWO));

        // second test: generator g4 regulates reactive power on line 3->4 (on the opposite side of the line)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
          .withTargetQ(targetQ)
          .withRegulatingTerminal(l34.getTerminal(TwoSides.ONE))
          .withEnabled(true).add();

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, l34.getTerminal(TwoSides.ONE));

        // third test: generator g4 regulates reactive power on line 1->2 (line which is not linked to bus 4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
          .withTargetQ(targetQ)
          .withRegulatingTerminal(l12.getTerminal(TwoSides.ONE))
          .withEnabled(true).add();

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, l12.getTerminal(TwoSides.ONE));
    }

    @Test
    void testGeneratorRemoteReactivePowerControlOnZeroImpedanceBranch() {
        // create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");
        l34.setR(0).setX(0);

        double targetQ = 1.0;

        // disable voltage control on g4
        g4.setTargetQ(0).setVoltageRegulatorOn(false);

        // generator g4 regulates reactive power on line 4->3 (on side of g4)
        // which is zero impedant
        g4.newExtension(RemoteReactivePowerControlAdder.class)
            .withTargetQ(targetQ)
            .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
            .withEnabled(true).add();

        parametersExt
                .setGeneratorReactivePowerRemoteControl(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, l34.getTerminal(TwoSides.TWO));
    }

    @Test
    void testDiscardedGeneratorRemoteReactivePowerControls() {
        // create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");
        l34.getTerminal1().disconnect();

        double targetQ = 1.0;

        // disable voltage control on g4
        g4.setTargetQ(0).setVoltageRegulatorOn(false);

        // first test: generator g4 regulates reactive power on line 4->3 (on side of g4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(0.0, l34.getTerminal(TwoSides.TWO));

        l34.getTerminal2().disconnect();
        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);
        assertTrue(result2.isFullyConverged());
        assertReactivePowerEquals(Double.NaN, l34.getTerminal(TwoSides.TWO));
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControl() throws IOException {
        // we create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Bus b1 = network.getBusBreakerView().getBus("b1");
        Bus b2 = network.getBusBreakerView().getBus("b2");
        Bus b4 = network.getBusBreakerView().getBus("b4");
        Generator g4 = network.getGenerator("g4");
        Generator g1 = network.getGenerator("g1");
        Line l34 = network.getLine("l34");
        createGenerator(b2, "g2", 0);

        double targetQ = 1.0;

        // we disable the voltage control of g1 and g4
        g1.setTargetQ(0).setVoltageRegulatorOn(false);
        g4.setTargetQ(0).setVoltageRegulatorOn(false);

        // generators g1 and g4 both regulate reactive power on line 4->3
        g1.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(1, l34.getTerminal(TwoSides.TWO));
        assertEquals(0.0, Math.abs(b1.getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), DELTA_POWER);
        assertEquals(0.0, Math.abs(b4.getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), DELTA_POWER);

        // generators g1 and g4 both regulate reactive power on line 4->3
        g1.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.ONE))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        LoadFlowResult result2 = loadFlowRunner.run(network, VariantManagerConstants.INITIAL_VARIANT_ID, LocalComputationManager.getDefault(), parameters, reportNode);
        assertTrue(result2.isFullyConverged());
        assertReactivePowerEquals(1, l34.getTerminal(TwoSides.TWO));
        assertEquals(0.0, Math.abs(b1.getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), DELTA_POWER);
        assertEquals(0.0, Math.abs(b4.getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), DELTA_POWER);
        LoadFlowAssert.assertReportEquals("/sharedGeneratorRemoteReactivePowerControlReport.txt", reportNode);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControl2() {
        // generators g1 and g4 both regulate reactive power on line 4->3
        // reactive keys are not set -> fallback case: equally distributed
        Network network1 = FourBusNetworkFactory.createWithReactiveControl();
        Generator g4n1 = network1.getGenerator("g4");
        Generator g1n1 = network1.getGenerator("g1");
        Line l34n1 = network1.getLine("l34");

        parametersExt
                .setGeneratorReactivePowerRemoteControl(true)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxReactivePowerMismatch(DELTA_POWER); // needed to ensure convergence within a DELTA_POWER
                                                           // tolerance in Q for the controlled branch

        LoadFlowResult result1 = loadFlowRunner.run(network1, parameters);
        assertTrue(result1.isFullyConverged());
        assertReactivePowerEquals(2, l34n1.getTerminal(TwoSides.TWO));
        // reactive power equally partitioned
        assertEquals(Math.abs(g1n1.getTerminal().getQ()), Math.abs(g4n1.getTerminal().getQ()), DELTA_POWER);

        // generators g1 and g4 both regulate reactive power on line 4->3
        // reactive keys are set -> 75% for g1 and 25% for g4
        Network network2 = FourBusNetworkFactory.createWithReactiveControl();
        Line l34n2 = network2.getLine("l34");
        Generator g4n2 = network2.getGenerator("g4");
        Generator g1n2 = network2.getGenerator("g1");
        g1n2.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(75).add();
        g4n2.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(25).add();

        LoadFlowResult result2 = loadFlowRunner.run(network2, parameters);
        assertTrue(result2.isFullyConverged());
        assertReactivePowerEquals(2, l34n2.getTerminal(TwoSides.TWO));
        // reactive power partitioned 1:3
        assertEquals(Math.abs(g1n2.getTerminal().getQ()), 3 * Math.abs(g4n2.getTerminal().getQ()), DELTA_POWER);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControl3() {
        // only generator g1 regulates reactive power on line 4->3
        Network network1 = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1n1 = network1.getGenerator("g1");
        network1.getGenerator("g1Bis").getExtension(RemoteReactivePowerControl.class).setEnabled(false);
        Line l34n1 = network1.getLine("l34");

        parametersExt.setGeneratorReactivePowerRemoteControl(true);
        LoadFlowResult result1 = loadFlowRunner.run(network1, parameters);
        assertTrue(result1.isFullyConverged());
        assertReactivePowerEquals(2, l34n1.getTerminal(TwoSides.TWO));

        // both generators g1 and g1Bis (same bus) regulate reactive power on line 4->3 equally
        Network network2 = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1n2 = network2.getGenerator("g1");
        Line l34n2 = network2.getLine("l34");

        LoadFlowResult result2 = loadFlowRunner.run(network2, parameters);
        assertTrue(result2.isFullyConverged());
        assertReactivePowerEquals(2, l34n2.getTerminal(TwoSides.TWO));

        // in second run reactive power is divided by 2 due to the presence of the second generator g1Bis
        assertEquals(g1n1.getTerminal().getQ(), 2 * g1n2.getTerminal().getQ(), DELTA_POWER);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControl4() {
        // generators g1, g1Bis and g4 regulate reactive power on line 4->3
        Network network = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBusAnd1Extra();
        Generator g1 = network.getGenerator("g1");
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");

        parametersExt.setGeneratorReactivePowerRemoteControl(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(2, l34.getTerminal(TwoSides.TWO));
        // reactive power partitioned 2 (bus1 with 2 generators) : 1 (bus4)
        assertEquals(Math.abs(g1.getTerminal().getQ()), Math.abs(g4.getTerminal().getQ()), DELTA_POWER);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControlReactiveKeys() {
        // generator g1 and g1Bis regulate reactive power on line 4->3
        // they are on the same bus
        Network network = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1 = network.getGenerator("g1");
        Generator g1Bis = network.getGenerator("g1Bis");
        Line l34 = network.getLine("l34");

        // Q should be split 1 : 4 for g1 and g1Bis respectively
        g1.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(80).add();
        g1Bis.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(20).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true)
                .setReactivePowerDispatchMode(ReactivePowerDispatchMode.Q_EQUAL_PROPORTION);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(2, l34.getTerminal(TwoSides.TWO));
        assertEquals(g1.getTerminal().getQ(), 4 * g1Bis.getTerminal().getQ(), DELTA_POWER);
        assertEquals(5.8386, g1.getTerminal().getQ() + g1Bis.getTerminal().getQ(), DELTA_POWER);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControlReactiveKeysFallbackMaxRange() {
        // generator g1 and g1Bis regulate reactive power on line 4->3
        // they are on the same bus
        Network network = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1 = network.getGenerator("g1");
        Generator g1Bis = network.getGenerator("g1Bis");
        Line l34 = network.getLine("l34");

        // no reactive keys set => fallback
        // Q should be split 1 : 2 for g1 and g1Bis respectively
        g1.newMinMaxReactiveLimits().setMinQ(-20).setMaxQ(20).add();
        g1Bis.newMinMaxReactiveLimits().setMinQ(-10).setMaxQ(10).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true)
                .setReactivePowerDispatchMode(ReactivePowerDispatchMode.Q_EQUAL_PROPORTION);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(2, l34.getTerminal(TwoSides.TWO));
        assertEquals(g1.getTerminal().getQ(), 2 * g1Bis.getTerminal().getQ(), DELTA_POWER);
        assertEquals(5.8386, g1.getTerminal().getQ() + g1Bis.getTerminal().getQ(), DELTA_POWER);
    }

    @Test
    void testSharedGeneratorRemoteReactivePowerControlReactiveKeysFallbackEquallyDistributed() {
        // generator g1 and g1Bis regulate reactive power on line 4->3
        // they are on the same bus
        Network network = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1 = network.getGenerator("g1");
        Generator g1Bis = network.getGenerator("g1Bis");
        Line l34 = network.getLine("l34");

        // no reactive keys set => fallback max range
        // not valid max ranges => fallback equally distributed
        // Q should be equally split between g1 and g1Bis

        parametersExt.setGeneratorReactivePowerRemoteControl(true)
                .setReactivePowerDispatchMode(ReactivePowerDispatchMode.Q_EQUAL_PROPORTION);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(2, l34.getTerminal(TwoSides.TWO));
        assertEquals(g1.getTerminal().getQ(), g1Bis.getTerminal().getQ(), DELTA_POWER);
        assertEquals(5.8386, g1.getTerminal().getQ() + g1Bis.getTerminal().getQ(), DELTA_POWER);
    }

    @Test
    void testNotSupportedGeneratorRemoteReactivePowerControl() {
        // Create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");

        double targetQ = 1.0;

        // first test: generator g4 regulates reactive power on line 4->3 (on side of g4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(0.274417, l34.getTerminal(TwoSides.TWO));
    }

    @Test
    void testGeneratorUpdateWithReactivePowerControlDisabled() {
        // control is off through parameters
        Network network2 = FourBusNetworkFactory.createWithReactiveControl2GeneratorsOnSameBus();
        Generator g1n1 = network2.getGenerator("g1Bis");
        g1n1.setTargetQ(5.0);
        Generator g1n2 = network2.getGenerator("g1");
        g1n2.setTargetQ(3.0);
        Line l34n2 = network2.getLine("l34");
        LoadFlowResult result2 = loadFlowRunner.run(network2, parameters);
        assertTrue(result2.isFullyConverged());
        assertReactivePowerEquals(-0.287, l34n2.getTerminal(TwoSides.TWO));
        assertReactivePowerEquals(-5.0, g1n1.getTerminal());
        assertReactivePowerEquals(-3.0, g1n2.getTerminal());
    }

    @Test
    void testNotSupportedGeneratorRemoteReactivePowerControl2() {
        Network network = FourBusNetworkFactory.createWithTwoGeneratorsAtBus2();
        Generator g2 = network.getGenerator("g2");
        Line l34 = network.getLine("l34");

        double targetQ = 1.0;

        // generator g2 regulates reactive power on line 4->3
        // generator g5 regulates voltage
        // they are both connected to the same bus
        g2.setTargetQ(0).setVoltageRegulatorOn(false);
        g2.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(0.162232, l34.getTerminal(TwoSides.TWO));
    }

    @Test
    void testNotSupportedGeneratorRemoteReactivePowerControl3() {
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Load l = network.getLoad("d2");

        double targetQ = 1.0;

        g4.setTargetQ(0).setVoltageRegulatorOn(false);
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l.getTerminal()) // not supported.
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
    }

    @Test
    void testGeneratorRemoteReactivePowerControl2() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        VoltageLevel vlload = network.getVoltageLevel("VLLOAD");
        Bus nload = vlload.getBusBreakerView().getBus("NLOAD");
        vlload.newGenerator()
                .setId("GEN2")
                .setBus(nload.getId())
                .setConnectableBus(nload.getId())
                .setMinP(-9999.99D)
                .setMaxP(9999.99D)
                .setVoltageRegulatorOn(true)
                .setTargetV(150D)
                .setTargetP(0.0D)
                .setTargetQ(301.0D)
                .add();
        Generator generator2 = network.getGenerator("GEN2");
        generator2.newReactiveCapabilityCurve()
                .beginPoint()
                .setP(3.0D)
                .setMaxQ(5.0D)
                .setMinQ(4.0D)
                .endPoint()
                .beginPoint()
                .setP(0.0D)
                .setMaxQ(7.0D)
                .setMinQ(6.0D)
                .endPoint()
                .beginPoint()
                .setP(1.0D)
                .setMaxQ(5.0D)
                .setMinQ(4.0D)
                .endPoint()
                .add();

        Generator gen = network.getGenerator("GEN");
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer("NGEN_NHV1");

        double targetQ = 1.0;

        gen.setTargetQ(0).setVoltageRegulatorOn(false);

        gen.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(twt.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, twt.getTerminal(TwoSides.TWO));
    }

    /**
     * G1 controls reactive power on T3WT leg1.
     *<pre>
     *     G1        LD2        LD3   G3
     *     |    L12   |          |   /
     *     | -------- |          |  /
     *     B1         B2         B3
     *                  \        /
     *                leg1     leg2
     *                   \      /
     *                     T3WT
     *                      |
     *                     leg3
     *                      |
     *                      B4
     *                      |
     *                     LD4
     *</pre>
     */
    @Test
    void testGeneratorRemoteReactivePowerControl3wt() {
        Network network = VoltageControlNetworkFactory.createNetworkWithT3wt();

        var gen1 = network.getGenerator("GEN_1");
        var t3wt = network.getThreeWindingsTransformer("T3wT");

        VoltageLevel vl3 = network.getVoltageLevel("VL_3");
        Bus b3 = vl3.getBusBreakerView().getBus("BUS_3");
        vl3.newGenerator()
                .setId("GEN_3")
                .setBus(b3.getId())
                .setConnectableBus(b3.getId())
                .setMinP(-100)
                .setMaxP(+100)
                .setVoltageRegulatorOn(true)
                .setTargetV(vl3.getNominalV())
                .setTargetP(10.0)
                .setTargetQ(0.0)
                .add();

        double targetQ = -5.0;
        gen1.setTargetQ(0.0).setVoltageRegulatorOn(false)
                .newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(t3wt.getTerminal(ThreeSides.ONE))
                .withEnabled(true).add();

        parametersExt
                .setGeneratorReactivePowerRemoteControl(true)
                .setMaxReactivePowerMismatch(DELTA_POWER)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(targetQ, t3wt.getTerminal(ThreeSides.ONE));
    }

    @Test
    void testReactiveRangeCheckMode() {
        parameters.setUseReactiveLimits(true);
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        VoltageLevel vlload = network.getVoltageLevel("VLLOAD");
        Bus nload = vlload.getBusBreakerView().getBus("NLOAD");
        vlload.newGenerator()
                .setId("GEN2")
                .setBus(nload.getId())
                .setConnectableBus(nload.getId())
                .setMinP(-9999.99D)
                .setMaxP(9999.99D)
                .setVoltageRegulatorOn(true)
                .setTargetV(150D)
                .setTargetP(0.0D)
                .setTargetQ(301.0D)
                .add();
        Generator generator2 = network.getGenerator("GEN2");
        generator2.newReactiveCapabilityCurve()
                .beginPoint()
                .setP(3.0D)
                .setMaxQ(100)
                .setMinQ(-100)
                .endPoint()
                .beginPoint()
                .setP(0.0D)
                .setMaxQ(100)
                .setMinQ(-100)
                .endPoint()
                .beginPoint()
                .setP(1.0D)
                .setMaxQ(5.0D)
                .setMinQ(5.0D)
                .endPoint()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(150.0, nload);

        generator2.setTargetP(1.0);
        parametersExt.setReactiveRangeCheckMode(OpenLoadFlowParameters.ReactiveRangeCheckMode.TARGET_P);
        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);
        assertTrue(result2.isFullyConverged());
        assertVoltageEquals(164.88, nload);

        generator2.setTargetP(0.0);
        parametersExt.setReactiveRangeCheckMode(OpenLoadFlowParameters.ReactiveRangeCheckMode.MIN_MAX);
        LoadFlowResult result3 = loadFlowRunner.run(network, parameters);
        assertTrue(result3.isFullyConverged());
        assertVoltageEquals(164.88, nload);
    }

    @Test
    void testWithZeroReactiveKey() {
        g1.newExtension(CoordinatedReactiveControlAdder.class).withQPercent(0).add();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new LfNetworkParameters()).get(0);
        assertTrue(lfNetwork.getGeneratorById(g1.getId()).getRemoteControlReactiveKey().isEmpty()); // zero is fixed to empty
    }

    @Test
    void testGeneratorRemoteReactivePowerControlInsideReactiveLimits() {
        // create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");

        double targetQ = 4.0;

        // disable voltage control on g4
        g4.setTargetQ(0.0).setVoltageRegulatorOn(false);

        // first test: generator g4 regulates reactive power on line 4->3 (on side of g4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        g4.newMinMaxReactiveLimits().setMinQ(-15.0).setMaxQ(15.0).add();

        parameters.setUseReactiveLimits(true);
        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-10.296, g4.getTerminal());
        assertReactivePowerEquals(4.004, l34.getTerminal2());
        assertEquals(0.0, Math.abs(network.getBusView().getBus("b4_vl_0").getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), 1E-2);
    }

    @Test
    void testGeneratorRemoteReactivePowerControlOutsideReactiveLimits() {
        // create a basic 4-buses network
        Network network = FourBusNetworkFactory.createBaseNetwork();
        Generator g4 = network.getGenerator("g4");
        Line l34 = network.getLine("l34");

        double targetQ = 4.0;

        // disable voltage control on g4
        g4.setTargetQ(0.0).setVoltageRegulatorOn(false);

        // first test: generator g4 regulates reactive power on line 4->3 (on side of g4)
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(targetQ)
                .withRegulatingTerminal(l34.getTerminal(TwoSides.TWO))
                .withEnabled(true).add();

        g4.newMinMaxReactiveLimits().setMinQ(-5.0).setMaxQ(5.0).add();

        parameters.setUseReactiveLimits(true);
        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-5.0, g4.getTerminal());
        assertReactivePowerEquals(2.031, l34.getTerminal2());
        assertEquals(0.0, Math.abs(network.getBusView().getBus("b4_vl_0").getConnectedTerminalStream().mapToDouble(Terminal::getQ).sum()), 1E-2);
    }
}
