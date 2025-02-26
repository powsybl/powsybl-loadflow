/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.outerloop.AcIncrementalPhaseControlOuterLoop;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class AcLoadFlowPhaseShifterTest {

    private Network network;
    private Bus bus1;
    private Bus bus2;
    private Bus bus3;
    private Bus bus4;
    private Line line1;
    private Line line2;
    private TwoWindingsTransformer t2wt;
    private ThreeWindingsTransformer t3wt;

    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;
    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);
    }

    @Test
    void baseCaseT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertVoltageEquals(400, bus1);
        assertAngleEquals(0, bus1);
        assertVoltageEquals(385.698, bus2);
        assertAngleEquals(-3.679569, bus2);
        assertVoltageEquals(392.648, bus3);
        assertAngleEquals(-1.806254, bus3);
        assertActivePowerEquals(50.089, line1.getTerminal1());
        assertReactivePowerEquals(29.192, line1.getTerminal1());
        assertActivePowerEquals(-50.005, line1.getTerminal2());
        assertReactivePowerEquals(-24.991, line1.getTerminal2());
        assertActivePowerEquals(50.048, line2.getTerminal1());
        assertReactivePowerEquals(27.097, line2.getTerminal1());
        assertActivePowerEquals(-50.006, line2.getTerminal2());
        assertReactivePowerEquals(-24.996, line2.getTerminal2());
    }

    @Test
    void tapPlusOneT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        t2wt.getPhaseTapChanger().setTapPosition(2);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertVoltageEquals(400, bus1);
        assertAngleEquals(0, bus1);
        assertVoltageEquals(385.296, bus2);
        assertAngleEquals(-1.186517, bus2);
        assertVoltageEquals(392.076, bus3);
        assertAngleEquals(1.964715, bus3);
        assertActivePowerEquals(16.541, line1.getTerminal1());
        assertReactivePowerEquals(29.241, line1.getTerminal1());
        assertActivePowerEquals(-16.513, line1.getTerminal2());
        assertReactivePowerEquals(-27.831, line1.getTerminal2());
        assertActivePowerEquals(83.587, line2.getTerminal1());
        assertReactivePowerEquals(27.195, line2.getTerminal1());
        assertActivePowerEquals(-83.487, line2.getTerminal2());
        assertReactivePowerEquals(-22.169, line2.getTerminal2());
    }

    @Test
    void flowControlT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(83.587, line2.getTerminal1());
        assertActivePowerEquals(-83.486, line2.getTerminal2());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());

        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationValue(83)
                .setRegulationTerminal(t2wt.getTerminal2());

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(16.528, line2.getTerminal1());
        assertActivePowerEquals(-16.514, line2.getTerminal2());
        assertEquals(0, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void remoteFlowControlT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(line1.getTerminal1())
                .setRegulationValue(83);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(83.688, line1.getTerminal1());
        assertActivePowerEquals(16.527, line2.getTerminal1());
        assertEquals(0, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void currentLimiterT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(false)
                .setTapPosition(2)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83); // in A

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertCurrentEquals(129.436, t2wt.getTerminal1());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());

        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83); // in A

        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);
        assertTrue(result2.isFullyConverged());
        assertCurrentEquals(48.482, t2wt.getTerminal1());
        assertEquals(0, t2wt.getPhaseTapChanger().getTapPosition());

        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(90); // A

        LoadFlowResult result3 = loadFlowRunner.run(network, parameters);
        assertTrue(result3.isFullyConverged());
        assertCurrentEquals(83.680, line2.getTerminal1());
        assertEquals(1, t2wt.getPhaseTapChanger().getTapPosition());

        t2wt.getPhaseTapChanger().getStep(0).setAlpha(5.);
        t2wt.getPhaseTapChanger().getStep(1).setAlpha(0.);
        t2wt.getPhaseTapChanger().getStep(2).setAlpha(-5.);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83); // A

        LoadFlowResult result4 = loadFlowRunner.run(network, parameters);
        assertTrue(result4.isFullyConverged());
        assertCurrentEquals(48.492, line2.getTerminal1());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void openT2wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83);
        t2wt.getTerminal1().disconnect();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void regulatingTerminalDisconnectedTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        Line line = network.getLine("L2");
        line.getTerminal2().disconnect();

        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(line.getTerminal2())
                .setRegulationValue(83);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void nullControlledBranchTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(network.getLoad("LD2").getTerminal())
                .setRegulationValue(83);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void openControlledBranchTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        network.newLine()
                .setId("L3")
                .setConnectableBus1("B1")
                .setBus1("B1")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(10.0)
                .add();
        line1.getTerminal1().disconnect();
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(line1.getTerminal1())
                .setRegulationValue(83);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
    }

    @Test
    void baseCaseT3wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT3wt());
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertVoltageEquals(400, bus1);
        assertAngleEquals(0, bus1);
        assertVoltageEquals(378.252, bus2);
        assertAngleEquals(-3.635322112477251, bus2);
        assertVoltageEquals(381.456, bus3);
        assertAngleEquals(-2.6012527358034787, bus3);
        assertVoltageEquals(372.162, bus4);
        assertAngleEquals(-2.550385462101632, bus4);
        assertActivePowerEquals(48.848, line1.getTerminal1());
        assertReactivePowerEquals(44.041, line1.getTerminal1());
        assertActivePowerEquals(-48.739, line1.getTerminal2());
        assertReactivePowerEquals(-38.634, line1.getTerminal2());
        assertActivePowerEquals(26.278, line2.getTerminal1());
        assertReactivePowerEquals(11.9308, line2.getTerminal1());
        assertActivePowerEquals(-26.266, line2.getTerminal2());
        assertReactivePowerEquals(-11.358, line2.getTerminal2());
    }

    @Test
    void tapPlusOneT3wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT3wt());
        t3wt.getLeg2().getPhaseTapChanger().setTapPosition(2);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertVoltageEquals(400, bus1);
        assertAngleEquals(0, bus1);
        assertVoltageEquals(377.782, bus2);
        assertAngleEquals(-5.697417220150056, bus2);
        assertVoltageEquals(381.293, bus3);
        assertAngleEquals(-5.737530604739468, bus3);
        assertVoltageEquals(372.172, bus4);
        assertAngleEquals(-1.6404666559146515, bus4);
        assertActivePowerEquals(75.941, line1.getTerminal1());
        assertReactivePowerEquals(46.650, line1.getTerminal1());
        assertActivePowerEquals(-75.742, line1.getTerminal2());
        assertReactivePowerEquals(-36.721, line1.getTerminal2());
        assertActivePowerEquals(-0.740, line2.getTerminal1());
        assertReactivePowerEquals(13.403, line2.getTerminal1());
        assertActivePowerEquals(0.743, line2.getTerminal2());
        assertReactivePowerEquals(-13.279, line2.getTerminal2());
    }

    @Test
    void flowControlT3wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT3wt());
        parameters.setPhaseShifterRegulationOn(true);
        t3wt.getLeg2().getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t3wt.getLeg2().getTerminal())
                .setRegulationValue(0.);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-0.7403999884197101, line2.getTerminal1());
        assertActivePowerEquals(0.7428793087142719, line2.getTerminal2());
        assertEquals(2, t3wt.getLeg2().getPhaseTapChanger().getTapPosition());
    }

    @Test
    void remoteFlowControlT3wtTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT3wt());
        parameters.setPhaseShifterRegulationOn(true);
        t3wt.getLeg2().getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(line1.getTerminal1())
                .setRegulationValue(75);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(75.94143342722937, line1.getTerminal1());
        assertEquals(2, t3wt.getLeg2().getPhaseTapChanger().getTapPosition());
    }

    @Test
    void ratioAndPhaseTapChangerTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        t2wt.getPhaseTapChanger().setTapPosition(2);
        t2wt.newRatioTapChanger()
                .setLoadTapChangingCapabilities(false)
                .setTapPosition(0)
                .beginStep()
                    .setRho(0.9)
                .endStep()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        assertActivePowerEquals(76.830, t2wt.getTerminal1());
        assertReactivePowerEquals(-4.922, t2wt.getTerminal1());
        assertActivePowerEquals(-76.738, t2wt.getTerminal2());
        assertReactivePowerEquals(9.495, t2wt.getTerminal2());
    }

    @Test
    void nonSupportedPhaseControl() {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer("l45");
        parameters.setPhaseShifterRegulationOn(true);
        twt.getPhaseTapChanger().setRegulationTerminal(network.getLine("l12").getTerminal1())
                .setRegulationValue(0)
                .setTargetDeadband(1)
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setRegulating(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(100.0805, network.getLine("l12").getTerminal1());
    }

    @Test
    void nonSupportedPhaseControl2() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1) // FIXME how to take this into account
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(line1.getTerminal1())
                .setRegulationValue(83);
        t2wt.getTerminal1().disconnect();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(1, t2wt.getPhaseTapChanger().getTapPosition());
    }

    private void selectNetwork(Network network) {
        this.network = network;
        bus1 = network.getBusBreakerView().getBus("B1");
        bus2 = network.getBusBreakerView().getBus("B2");
        bus3 = network.getBusBreakerView().getBus("B3");
        bus4 = network.getBusBreakerView().getBus("B4");

        line1 = network.getLine("L1");
        line2 = network.getLine("L2");
        t2wt = network.getTwoWindingsTransformer("PS1");
        t3wt = network.getThreeWindingsTransformer("PS1");
    }

    @Test
    void testPhaseShifterNecessaryForConnectivity() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());

        // remove L1 so that PS1 loss would break connectivity
        line1.getTerminal1().disconnect();
        line1.getTerminal2().disconnect();

        // switch PS1 to active power control
        t2wt.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setRegulationValue(83);

        parameters.setPhaseShifterRegulationOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(100.3689, t2wt.getTerminal1());
        assertActivePowerEquals(-100.1844, t2wt.getTerminal2());
    }

    @Test
    void incrementalPhaseShifterCurrentLimiterTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        t2wt.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1)
                .setRegulating(false)
                .setTapPosition(2)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83); // in A

        parameters.setPhaseShifterRegulationOn(true);
        parametersExt.setPhaseShifterControlMode(OpenLoadFlowParameters.PhaseShifterControlMode.INCREMENTAL);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertCurrentEquals(129.436, t2wt.getTerminal1());

        t2wt.getPhaseTapChanger().setRegulating(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertCurrentEquals(48.482, t2wt.getTerminal1());
    }

    // TODO: Understand why ot fails with vector engine, fix and reconnect
    @Disabled("Need to be restored - understand why it fails with vector engine")
    @Test
    void incrementalPhaseShifterActivePowerControlTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        t2wt.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(false)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(100); // in MW

        parameters.setPhaseShifterRegulationOn(true);
        parametersExt.setPhaseShifterControlMode(OpenLoadFlowParameters.PhaseShifterControlMode.INCREMENTAL);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(50.088, t2wt.getTerminal1());

        t2wt.getPhaseTapChanger().setRegulating(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());
        assertActivePowerEquals(83.686, t2wt.getTerminal1());

        t2wt.getPhaseTapChanger().setRegulationTerminal(t2wt.getTerminal2());
        t2wt.getPhaseTapChanger().setRegulationValue(10);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0, t2wt.getPhaseTapChanger().getTapPosition());
        assertActivePowerEquals(16.541, t2wt.getTerminal1());
    }

    @Test
    void incrementalPhaseShifterSensiTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        t2wt.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.CURRENT_LIMITER)
                .setTargetDeadband(1)
                .setRegulating(false)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(83); // in A

        parameters.setPhaseShifterRegulationOn(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        double i1t1 = t2wt.getTerminal1().getI();
        double i2t1 = t2wt.getTerminal1().getI();

        t2wt.getPhaseTapChanger().setTapPosition(0);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        double i1t0 = t2wt.getTerminal1().getI();
        double i2t0 = t2wt.getTerminal1().getI();

        t2wt.getPhaseTapChanger().setTapPosition(2);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        double i1t2 = t2wt.getTerminal1().getI();
        double i2t2 = t2wt.getTerminal1().getI();

        double di1t10 = i1t1 - i1t0;
        double di2t10 = i2t1 - i2t0;
        double di1t12 = i1t1 - i1t2;
        double di2t12 = i2t1 - i2t2;
        assertEquals(35.183506011159274, di1t10, 0d);
        assertEquals(35.183506011159274, di2t10, 0d);
        assertEquals(-45.76103264996253, di1t12, 0d);
        assertEquals(-45.76103264996253, di2t12, 0d);

        // compare with sensi on tap 1
        t2wt.getPhaseTapChanger().setTapPosition(1);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        t2wt.getPhaseTapChanger().setRegulating(true);

        LfNetworkParameters lfNetworkParameters = new LfNetworkParameters()
                .setPhaseControl(true);
        LfNetwork lfNetwork = Networks.load(network, lfNetworkParameters).get(0);
        AcLoadFlowParameters acParameters = new AcLoadFlowParameters()
                .setNetworkParameters(lfNetworkParameters)
                .setMatrixFactory(new DenseMatrixFactory());
        try (AcLoadFlowContext lfContext = new AcLoadFlowContext(lfNetwork, acParameters)) {
            AcLoadFlowResult lfResult = new AcloadFlowEngine(lfContext)
                    .run();
            assertEquals(AcSolverStatus.CONVERGED, lfResult.getSolverStatus());
            LfBranch ps1 = lfNetwork.getBranchById("PS1");
            List<LfBranch> controllerBranches = List.of(ps1);
            var sensitivityContext = new AcIncrementalPhaseControlOuterLoop.AcSensitivityContext(lfNetwork,
                                                                                                 controllerBranches,
                                                                                                 lfContext.getEquationSystem(),
                                                                                                 lfContext.getJacobianMatrix());
            double da10 = t2wt.getPhaseTapChanger().getStep(1).getAlpha() - t2wt.getPhaseTapChanger().getStep(0).getAlpha();
            double da12 = t2wt.getPhaseTapChanger().getStep(1).getAlpha() - t2wt.getPhaseTapChanger().getStep(2).getAlpha();
            double ib = PerUnit.ib(ps1.getBus1().getNominalV());
            double sensi1 = sensitivityContext.calculateSensitivityFromA2I(ps1, ps1, TwoSides.ONE);
            double di1t10p = sensi1 * da10 * ib;
            double di1t12p = sensi1 * da12 * ib;
            assertEquals(43.007011829925496, di1t10p, 0d);
            assertEquals(-43.007011829925496, di1t12p, 0d);
            double sensi2 = sensitivityContext.calculateSensitivityFromA2I(ps1, ps1, TwoSides.TWO);
            double di2t10p = sensi2 * da10 * ib;
            double di2t12p = sensi2 * da12 * ib;
            assertEquals(43.007011829925496, di2t10p, 0d);
            assertEquals(-43.007011829925496, di2t12p, 0d);
        }
    }

    @Test
    void activePowerflowControlAndNonImpedantPhaseShifterTest() {
        selectNetwork(PhaseControlFactory.createNetworkWithT2wt());
        parameters.setPhaseShifterRegulationOn(true);
        t2wt.setR(0).setX(0);
        t2wt.getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationValue(100);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(112.197, line2.getTerminal1());
        assertActivePowerEquals(-112.019, line2.getTerminal2());
        assertEquals(2, t2wt.getPhaseTapChanger().getTapPosition());

        line1.setR(0.0).setX(0.0);
        t2wt.setR(2.0).setX(100.0);
        t2wt.getPhaseTapChanger()
                .setRegulationTerminal(line1.getTerminal1())
                .setTapPosition(0);
        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);

        assertTrue(result2.isFullyConverged());
        assertActivePowerEquals(100.0, line1.getTerminal1());
        assertActivePowerEquals(0.0, line2.getTerminal1());
        assertEquals(1, t2wt.getPhaseTapChanger().getTapPosition());
    }
}
