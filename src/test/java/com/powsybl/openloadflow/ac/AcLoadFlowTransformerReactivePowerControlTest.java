/**
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.openloadflow.ac;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControlAdder;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.outerloop.ReactiveLimitsOuterLoop;
import com.powsybl.openloadflow.network.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Pierre Arvy {@literal <pierre.arvy at artelys.com>}
 */
class AcLoadFlowTransformerReactivePowerControlTest {

    private Network network;
    private TwoWindingsTransformer t2wt;
    private TwoWindingsTransformer t2wt2;
    private ThreeWindingsTransformer t3wt;

    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;
    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters();
        parameters.setTransformerVoltageControlOn(false);
        parameters.setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);

        // Activate trace logs to ensure ReactiveLimitsOuterLoop traces are run once per build
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(ReactiveLimitsOuterLoop.class).setLevel(Level.TRACE);
    }

    @AfterEach
    void restoreLogger() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(ReactiveLimitsOuterLoop.class).setLevel(null);
    }

    @Test
    void baseCaseT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.618, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.318, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.181, t2wt.getTerminal1());
        assertReactivePowerEquals(3.205e-5, t2wt.getTerminal2());
    }

    @Test
    void testGeneratorRemoteReactivePowerControlOutsideReactiveLimits() throws IOException {
        Network network = ReactivePowerControlNetworkFactory.create4BusNetworkWithRatioTapChanger();

        // controllers of reactive power
        Generator g4 = network.getGenerator("g4");
        TwoWindingsTransformer t2wt = network.getTwoWindingsTransformer("l34");

        double gTargetQ = 3.155;
        double t2wtTargetQ = 1;
        Terminal regulatedTerminal = t2wt.getTerminal2();

        g4.setTargetQ(0.0).setVoltageRegulatorOn(false);
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(gTargetQ)
                .withRegulatingTerminal(regulatedTerminal)
                .withEnabled(true).add();
        g4.newMinMaxReactiveLimits().setMinQ(-5.0).setMaxQ(5.0).add();

        t2wt.getRatioTapChanger()
                .setLoadTapChangingCapabilities(true)
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setTargetDeadband(0)
                .setRegulationValue(t2wtTargetQ)
                .setRegulationTerminal(regulatedTerminal)
                .setRegulating(true);

        // without transformer regulating, generator does not hold its target
        parameters.setUseReactiveLimits(true);
        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-5.0, g4.getTerminal());
        assertReactivePowerEquals(2.588, regulatedTerminal); // not targetQ

        // without generator regulating, transformer does not hold its target
        parametersExt.setGeneratorReactivePowerRemoteControl(false)
                .setTransformerReactivePowerControl(true);

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(0.891, regulatedTerminal);
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // with transformer/generator regulating, generator target Q is held
        t2wt.getRatioTapChanger().setTapPosition(1);
        parametersExt.setGeneratorReactivePowerRemoteControl(true)
                .setTransformerReactivePowerControl(true);

        ReportNode report = ReportNode.newRootReportNode().withMessageTemplate("test", "test").build();

        result = loadFlowRunner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), parameters, report);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(-5.0, g4.getTerminal()); // limit of generator
        assertReactivePowerEquals(gTargetQ, regulatedTerminal); // targetQ of generator is held
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // Test the report
        String expected = """
                + test
                   + Load flow on network 'test'
                      + Network CC0 SC0
                         + Network info
                            Network has 4 buses and 5 branches
                            Network balance: active generation=3.0 MW, active load=5.0 MW, reactive generation=0.0 MVar, reactive load=0.0 MVar
                            Angle reference bus: b1_vl_0
                            Slack bus: b1_vl_0
                         Outer loop VoltageMonitoring
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 1
                               + 1 bus(es) with remote reactive power controller switched PQ
                                  Remote reactive power controller bus 'b4_vl_0' -> PQ, q=6.39 > maxQ=5.0
                         + Outer loop IncrementalTransformerReactivePowerControl
                            + Outer loop iteration 2
                               1 reactive power-controlled branches are outside of their target deadbands
                               1 transformers changed tap position
                               1 transformers reached their tap maximum position
                            + Outer loop iteration 3
                               1 reactive power-controlled branches are outside of their target deadbands
                         Outer loop VoltageMonitoring
                         Outer loop ReactiveLimits
                         + Outer loop IncrementalTransformerReactivePowerControl
                            + Outer loop iteration 3
                               1 reactive power-controlled branches are outside of their target deadbands
                         AC load flow completed successfully (solverStatus=CONVERGED, outerloopStatus=STABLE)
                """;

        assertReportEquals(new ByteArrayInputStream(expected.getBytes()), report);
    }

    @Test
    void testGeneratorRemoteReactivePowerControlBelowReactiveLimits() throws IOException {
        Network myNetwork = ReactivePowerControlNetworkFactory.create4BusNetworkWithRatioTapChanger();

        // controllers of reactive power
        Generator g4 = myNetwork.getGenerator("g4");
        TwoWindingsTransformer twoWindingsTransformer = myNetwork.getTwoWindingsTransformer("l34");

        double gTargetQ = -1;
        double t2wtTargetQ = 1;
        Terminal regulatedTerminal = twoWindingsTransformer.getTerminal2();

        g4.setTargetQ(0.0).setVoltageRegulatorOn(false);
        g4.newExtension(RemoteReactivePowerControlAdder.class)
                .withTargetQ(gTargetQ)
                .withRegulatingTerminal(regulatedTerminal)
                .withEnabled(true).add();
        g4.newMinMaxReactiveLimits().setMinQ(-3.0).setMaxQ(5.0).add();

        twoWindingsTransformer.getRatioTapChanger()
                .setLoadTapChangingCapabilities(true)
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setTargetDeadband(0)
                .setRegulationValue(t2wtTargetQ)
                .setRegulationTerminal(regulatedTerminal)
                .setRegulating(true);

        // without transformer regulating, generator does not hold its target
        parameters.setUseReactiveLimits(true);
        parametersExt.setGeneratorReactivePowerRemoteControl(true);

        ReportNode report = ReportNode.newRootReportNode()
                .withMessageTemplate("test", "test")
                .build();

        LoadFlowResult result = loadFlowRunner.run(myNetwork, myNetwork.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(),
                parameters, report);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(3.0, g4.getTerminal());
        assertReactivePowerEquals(-0.750, regulatedTerminal); // not targetQ

        // Test the report
        String expected = """
                + test
                   + Load flow on network 'test'
                      + Network CC0 SC0
                         + Network info
                            Network has 4 buses and 5 branches
                            Network balance: active generation=3.0 MW, active load=5.0 MW, reactive generation=0.0 MVar, reactive load=0.0 MVar
                            Angle reference bus: b1_vl_0
                            Slack bus: b1_vl_0
                         Outer loop VoltageMonitoring
                         + Outer loop ReactiveLimits
                            + Outer loop iteration 1
                               + 1 bus(es) with remote reactive power controller switched PQ
                                  Remote reactive power controller bus 'b4_vl_0' -> PQ, q=-3.49 < minQ=-3.0
                         Outer loop VoltageMonitoring
                         Outer loop ReactiveLimits
                         AC load flow completed successfully (solverStatus=CONVERGED, outerloopStatus=STABLE)
                """;

        assertReportEquals(new ByteArrayInputStream(expected.getBytes()), report);

    }

    @Test
    void tapPlusThreeT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        t2wt.getRatioTapChanger().setTapPosition(3);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
    }

    @Test
    void transformerReactivePowerControlT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.55);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest2() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(3)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.55);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest3() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(7.6);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.618, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.318, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.181, t2wt.getTerminal1());
        assertReactivePowerEquals(3.205e-5, t2wt.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest4() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal2())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-7.3);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.611, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.311, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.186, t2wt.getTerminal1());
        assertReactivePowerEquals(0.0038, t2wt.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest5() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.48);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.362, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.021, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.479, t2wt.getTerminal1());
        assertReactivePowerEquals(7.654e-5, t2wt.getTerminal2());
        assertEquals(2, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest6() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-1);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.276, network.getLine("LINE_12").getTerminal1()); // FIXME shouldn't be 7.285 ?
        assertReactivePowerEquals(-6.918, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.579, t2wt.getTerminal1());
        assertReactivePowerEquals(0.006, t2wt.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void baseCase2T2wtTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.032, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.603, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.448, t2wt.getTerminal1());
        assertReactivePowerEquals(2.609e-6, t2wt.getTerminal2());
        assertReactivePowerEquals(-0.448, t2wt2.getTerminal1());
        assertReactivePowerEquals(2.609e-6, t2wt2.getTerminal2());
    }

    @Test
    void tapPlusThree2T2wtTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        t2wt.getRatioTapChanger().setTapPosition(3);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.436, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.891, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(2.462, t2wt.getTerminal1());
        assertReactivePowerEquals(-2.665, t2wt.getTerminal2());
        assertReactivePowerEquals(-3.071, t2wt2.getTerminal1());
        assertReactivePowerEquals(2.665, t2wt2.getTerminal2());
    }

    @Test
    void transformerReactivePowerControl2T2wtTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal2())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-6.89);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.436, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.891, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(2.462, t2wt.getTerminal1());
        assertReactivePowerEquals(-2.665, t2wt.getTerminal2());
        assertReactivePowerEquals(-3.071, t2wt2.getTerminal1());
        assertReactivePowerEquals(2.665, t2wt2.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControl2T2wtTest2() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0.1)
                .setRegulating(true)
                .setTapPosition(3)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-3);
        t2wt2.getRatioTapChanger()
                .setTargetDeadband(0.1)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-7.4);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.419, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.876, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-3.077, t2wt.getTerminal1());
        assertReactivePowerEquals(2.672, t2wt.getTerminal2());
        assertReactivePowerEquals(2.455, t2wt2.getTerminal1());
        assertReactivePowerEquals(-2.660, t2wt2.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
        assertEquals(3, t2wt2.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlOnNonImpedantBranch() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0.1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(7.3);

        network.getLine("LINE_12").setR(0).setX(0).setG1(0);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.308, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.308, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.192, t2wt.getTerminal1());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlNonImpedantRatioTapChanger() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wtAndSwitch());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt2.getRatioTapChanger()
                .setTargetDeadband(0.1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(3.89);

        t2wt2.setR(0).setX(0);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(3.891, network.getLine("LINE_12").getTerminal1());
        assertEquals(0, t2wt2.getRatioTapChanger().getTapPosition());
    }

    @Test
    void baseCaseT3wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT3wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.813, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.532, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(0.031, t3wt.getLeg1().getTerminal());
        assertReactivePowerEquals(0.003, t3wt.getLeg2().getTerminal());
        assertReactivePowerEquals(1.459e-4, t3wt.getLeg3().getTerminal());
    }

    @Test
    void tapPlusTwoT3wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT3wt());

        t3wt.getLeg2().getRatioTapChanger().setTapPosition(2);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertReactivePowerEquals(7.816, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.535, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(0.035, t3wt.getLeg1().getTerminal());
        assertReactivePowerEquals(8.076e-6, t3wt.getLeg2().getTerminal());
        assertReactivePowerEquals(6.698e-8, t3wt.getLeg3().getTerminal());
    }

    @Test
    void transformerReactivePowerControlT3wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT3wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t3wt.getLeg2().getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t3wt.getLeg2().getTerminal())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(0.035);

        parameters.setTransformerVoltageControlOn(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.816, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.535, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(0.035, t3wt.getLeg1().getTerminal());
        assertReactivePowerEquals(8.076e-6, t3wt.getLeg2().getTerminal());
        assertReactivePowerEquals(6.698e-8, t3wt.getLeg3().getTerminal());
        assertEquals(2, t3wt.getLeg2().getRatioTapChanger().getTapPosition());
    }

    @Test
    void openedControllerBranchTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt2.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-3.071);

        // no transformer reactive power control if terminal 2 is opened
        t2wt.getTerminal2().disconnect();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // no transformer reactive power control if terminal 1 is opened
        t2wt.getTerminal2().connect();
        t2wt.getTerminal1().disconnect();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void openedControlledBranchTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt2.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-3.071);

        // no transformer reactive power control if terminal 2 is opened on controlled branch
        t2wt2.getTerminal2().disconnect();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // no transformer reactive power control if terminal 1 is opened on controlled branch
        t2wt2.getTerminal2().connect();
        t2wt.getRatioTapChanger()
                .setRegulationTerminal(t2wt2.getTerminal2())
                .setRegulationValue(2.665);
        t2wt2.getTerminal1().disconnect();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void regulatingTerminalDisconnectedTransformerReactivePowerControlTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());
        Load load = network.getLoad("LOAD_2");
        load.getTerminal().disconnect();

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(load.getTerminal())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(33.0);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void twoControllersOnTheSameBranchTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parametersExt.setTransformerReactivePowerControl(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal2())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-6.89);
        t2wt2.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-6.603);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertReactivePowerEquals(7.436, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.891, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(2.462, t2wt.getTerminal1());
        assertReactivePowerEquals(-2.665, t2wt.getTerminal2());
        assertReactivePowerEquals(-3.071, t2wt2.getTerminal1());
        assertReactivePowerEquals(2.665, t2wt2.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
        assertEquals(0, t2wt2.getRatioTapChanger().getTapPosition());
    }

    private void selectNetwork(Network network) {
        this.network = network;

        t2wt = network.getTwoWindingsTransformer("T2wT");
        t3wt = network.getThreeWindingsTransformer("T3wT");
    }

    private void selectNetwork2(Network network) {
        this.network = network;

        t2wt = network.getTwoWindingsTransformer("T2wT1");
        t2wt2 = network.getTwoWindingsTransformer("T2wT2");
    }

}
