/**
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowProvider;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.solver.MaxVoltageChangeStateVectorScaling;
import com.powsybl.openloadflow.ac.solver.StateVectorScalingMode;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 */
class AcLoadFlowReportTest {

    @Test
    void testEsgTutoDetailedNrLogsLf() throws IOException {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testEsgTutoReport", "Test ESG tutorial report")
                .build();
        var lfParameters = new LoadFlowParameters()
                .setTransformerVoltageControlOn(true);
        var olfParameters = OpenLoadFlowParameters.create(lfParameters);
        olfParameters.setReportedFeatures(Set.of(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_LOAD_FLOW))
                     .setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL)
                     .setStateVectorScalingMode(StateVectorScalingMode.MAX_VOLTAGE_CHANGE)
                     .setMaxVoltageChangeStateVectorScalingMaxDv(MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DV / 10)
                     .setMaxVoltageChangeStateVectorScalingMaxDphi(MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DPHI / 10);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/esgTutoReportDetailedNrReportLf.txt", reportNode);
    }

    @Test
    void testShuntVoltageControlOuterLoopReport() throws IOException {
        Network network = ShuntNetworkFactory.createWithTwoShuntCompensators();
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);
        var olfParameters = OpenLoadFlowParameters.create(lfParameters);
        olfParameters.setReportedFeatures(Set.of(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_LOAD_FLOW))
                .setStateVectorScalingMode(StateVectorScalingMode.LINE_SEARCH)
                .setShuntVoltageControlMode(OpenLoadFlowParameters.ShuntVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/shuntVoltageControlOuterLoopReport.txt", reportNode);
    }

    @Test
    void testTransformerReactivePowerControlOuterLoopReport() throws IOException {
        Network network = VoltageControlNetworkFactory.createNetworkWithT2wt();
        var t2wt = network.getTwoWindingsTransformer("T2wT");
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.55);
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        var olfParameters = OpenLoadFlowParameters.create(lfParameters);
        olfParameters.setReportedFeatures(Set.of(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_LOAD_FLOW))
                .setTransformerReactivePowerControl(true);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/transformerReactivePowerControlOuterLoopReport.txt", reportNode);
    }

    @Test
    void testMultipleComponents() throws IOException {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();
        // open everything at bus b4 to create 3 components
        network.getBusBreakerView().getBus("b4").getConnectedTerminalStream().forEach(Terminal::disconnect);

        // CC1 SC1 has generator but no voltage control. OK in DC, but KO in AC.
        network.getGenerator("g6").setTargetQ(0.0).setVoltageRegulatorOn(false);
        // CC2 SC2 has no generator connected. Ignored in for DC and AC.
        network.getGenerator("g10").disconnect();

        var lfParameters = new LoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.ALL);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);

        // test in AC
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals("Converged", result.getComponentResults().get(0).getStatusText());
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED, result.getComponentResults().get(1).getStatus());
        assertEquals(LfNetwork.Validity.INVALID_NO_GENERATOR_VOLTAGE_CONTROL.toString(), result.getComponentResults().get(1).getStatusText());
        assertEquals(LoadFlowResult.ComponentResult.Status.NO_CALCULATION, result.getComponentResults().get(2).getStatus());
        assertEquals(LfNetwork.Validity.INVALID_NO_GENERATOR.toString(), result.getComponentResults().get(2).getStatusText());
        assertEquals(Double.NaN, network.getLoad("d9").getTerminal().getP()); // load on the NO_CALCULATION island connected component
        LoadFlowAssert.assertReportEquals("/multipleConnectedComponentsAcReport.txt", reportNode);

        // test in DC
        lfParameters.setDc(true);
        reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals("Converged", result.getComponentResults().get(0).getStatusText());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(1).getStatus());
        assertEquals("Converged", result.getComponentResults().get(1).getStatusText());
        assertEquals(LoadFlowResult.ComponentResult.Status.NO_CALCULATION, result.getComponentResults().get(2).getStatus());
        assertEquals(LfNetwork.Validity.INVALID_NO_GENERATOR.toString(), result.getComponentResults().get(2).getStatusText());
        assertEquals(Double.NaN, network.getLoad("d9").getTerminal().getP()); // load on the NO_CALCULATION island connected component
        LoadFlowAssert.assertReportEquals("/multipleConnectedComponentsDcReport.txt", reportNode);
    }

    @Test
    void generatorVoltageControlDiscarded() throws IOException {
        Network network = FourBusNetworkFactory.create();
        network.getGenerator("g2").setTargetV(10); // not plausible targetV, will be discarded and reported

        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        lfParameters.setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(lfParameters).setMinNominalVoltageTargetVoltageCheck(0.5);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/generatorVoltageControlDiscarded.txt", reportNode);
    }

    @Test
    void transformerVoltageControlDiscarded() throws IOException {
        Network network = VoltageControlNetworkFactory.createNetworkWithT2wt();
        var t2wt = network.getTwoWindingsTransformer("T2wT");
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.VOLTAGE)
                .setTargetV(100); // not plausible, will be discarded and reported
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        lfParameters.setTransformerVoltageControlOn(true);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/transformerVoltageControlDiscarded.txt", reportNode);
    }

    @Test
    void shuntVoltageControlDiscarded() throws IOException {
        Network network = ShuntNetworkFactory.createWithTwoShuntCompensators();
        network.getShuntCompensator("SHUNT2").setVoltageRegulatorOn(true).setTargetV(600); // not plausible targetV, will be discarded and reported
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/shuntVoltageControlDiscarded.txt", reportNode);
    }

    @Test
    void testTransformerControlAlreadyExistsWithDifferentTargetV() throws IOException {
        Network network = VoltageControlNetworkFactory.createWithTransformerSharedRemoteControl();
        network.getTwoWindingsTransformer("T2wT2").getRatioTapChanger().setTargetV(34.5).setTargetDeadband(3.0);
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        lfParameters.setTransformerVoltageControlOn(true);
        var olfParameters = OpenLoadFlowParameters.create(lfParameters);
        olfParameters.setReportedFeatures(Set.of(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_LOAD_FLOW));

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/transformerControlAlreadyExistsWithDifferentTargetVReport.txt", reportNode);
    }

    @Test
    void areaInterchangeControl() throws IOException {
        Network network = MultiAreaNetworkFactory.createTwoAreasWithXNode();
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters)
                .setAreaInterchangeControl(true);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        LoadFlowAssert.assertReportEquals("/areaInterchangeControlOuterLoop.txt", reportNode);
    }

    @Test
    void busesOutOfRealisticVoltageRangeTest() throws IOException {
        Network network = EurostagTutorialExample1Factory.create();
        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("testReport", "Test Report")
                .build();
        var lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters)
                .setMinRealisticVoltage(0.99)
                .setMaxRealisticVoltage(1.01);

        LoadFlowProvider provider = new OpenLoadFlowProvider(new DenseMatrixFactory(), new NaiveGraphConnectivityFactory<>(LfBus::getNum));
        LoadFlow.Runner runner = new LoadFlow.Runner(provider);
        LoadFlowResult result = runner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), lfParameters, reportNode);

        assertTrue(result.isFailed());
        LoadFlowAssert.assertTxtReportEquals("""
                + Test Report
                   + Load flow on network 'sim1'
                      + Network CC0 SC0
                         + Network info
                            Network has 4 buses and 4 branches
                            Network balance: active generation=607.0 MW, active load=600.0 MW, reactive generation=0.0 MVar, reactive load=200.0 MVar
                            Angle reference bus: VLHV1_0
                            Slack bus: VLHV1_0
                         + Outer loop DistributedSlack
                            + Outer loop iteration 1
                               Failed to distribute slack bus active power mismatch, -1.4404045651219555 MW remains
                         Outer loop VoltageMonitoring
                         Outer loop ReactiveLimits
                         + 4 buses have a voltage magnitude out of the configured realistic range [0.99, 1.01] p.u.
                            Bus VLGEN_0 has an unrealistic voltage magnitude: 1.0208333333333333 p.u.
                            Bus VLHV1_0 has an unrealistic voltage magnitude: 1.0582636574158686 p.u.
                            Bus VLHV2_0 has an unrealistic voltage magnitude: 1.0261840057810543 p.u.
                            Bus VLLOAD_0 has an unrealistic voltage magnitude: 0.9838500227734096 p.u.
                         AC load flow completed with error (solverStatus=UNREALISTIC_STATE, outerloopStatus=STABLE)
                            """, reportNode);
    }
}
