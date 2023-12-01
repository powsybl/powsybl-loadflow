/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
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
import com.powsybl.openloadflow.network.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters();
        parameters.setTransformerVoltageControlOn(false);
        parameters.setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.FIRST);
    }

    @Test
    void baseCaseT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());

        assertReactivePowerEquals(7.618, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.318, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.181, t2wt.getTerminal1());
        assertReactivePowerEquals(3.205e-5, t2wt.getTerminal2());
    }

    @Test
    void tapPlusThreeT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        t2wt.getRatioTapChanger().setTapPosition(3);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());

        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
    }

    @Test
    void transformerReactivePowerControlT2wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.55);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest2() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(3)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.55);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.285, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.927, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.573, t2wt.getTerminal1());
        assertReactivePowerEquals(5.170e-5, t2wt.getTerminal2());
        assertEquals(3, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest3() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(7.6);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.618, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.318, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.181, t2wt.getTerminal1());
        assertReactivePowerEquals(3.205e-5, t2wt.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest4() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal2())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-7.3);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.611, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.311, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.186, t2wt.getTerminal1());
        assertReactivePowerEquals(0.0038, t2wt.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest5() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-0.48);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.362, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.021, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-0.479, t2wt.getTerminal1());
        assertReactivePowerEquals(7.654e-5, t2wt.getTerminal2());
        assertEquals(2, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void transformerReactivePowerControlT2wtTest6() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal1())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-1);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
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
        assertTrue(result.isOk());

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
        assertTrue(result.isOk());

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

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(network.getLine("LINE_12").getTerminal2())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(-6.89);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
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

        parameters.setTransformerReactivePowerControlOn(true);
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
        assertTrue(result.isOk());
        assertReactivePowerEquals(7.436, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-6.891, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(-3.070, t2wt.getTerminal1());
        assertReactivePowerEquals(2.665, t2wt.getTerminal2());
        assertReactivePowerEquals(2.462, t2wt2.getTerminal1());
        assertReactivePowerEquals(-2.665, t2wt2.getTerminal2());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
        assertEquals(3, t2wt2.getRatioTapChanger().getTapPosition());
    }

    @Test
    void baseCaseT3wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT3wt());

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());

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
        assertTrue(result.isOk());

        assertReactivePowerEquals(7.816, network.getLine("LINE_12").getTerminal1());
        assertReactivePowerEquals(-7.535, network.getLine("LINE_12").getTerminal2());
        assertReactivePowerEquals(0.035, t3wt.getLeg1().getTerminal());
        assertReactivePowerEquals(8.076e-6, t3wt.getLeg2().getTerminal());
        assertReactivePowerEquals(6.698e-8, t3wt.getLeg3().getTerminal());
    }

    @Test
    void transformerReactivePowerControlT3wtTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT3wt());

        parameters.setTransformerReactivePowerControlOn(true);
        t3wt.getLeg2().getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(t3wt.getLeg2().getTerminal())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(0.035);

        parameters.setTransformerVoltageControlOn(true);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
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
        parameters.setTransformerReactivePowerControlOn(true);

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
        assertTrue(result.isOk());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // no transformer reactive power control if terminal 1 is opened
        t2wt.getTerminal2().connect();
        t2wt.getTerminal1().disconnect();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void openedControlledBranchTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());
        parameters.setTransformerReactivePowerControlOn(true);

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
        assertTrue(result.isOk());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());

        // no transformer reactive power control if terminal 1 is opened on controlled branch
        t2wt2.getTerminal2().connect();
        t2wt.getRatioTapChanger()
                .setRegulationTerminal(t2wt2.getTerminal2())
                .setRegulationValue(2.665);
        t2wt2.getTerminal1().disconnect();
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertEquals(0, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void regulatingTerminalDisconnectedTransformerReactivePowerControlTest() {
        selectNetwork(VoltageControlNetworkFactory.createNetworkWithT2wt());
        Load load = network.getLoad("LOAD_2");
        load.getTerminal().disconnect();

        parameters.setTransformerReactivePowerControlOn(true);
        t2wt.getRatioTapChanger()
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(load.getTerminal())
                .setRegulationMode(RatioTapChanger.RegulationMode.REACTIVE_POWER)
                .setRegulationValue(33.0);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isOk());
        assertEquals(2, t2wt.getRatioTapChanger().getTapPosition());
    }

    @Test
    void twoControllersOnTheSameBranchTest() {
        selectNetwork2(VoltageControlNetworkFactory.createNetworkWith2T2wt());

        parameters.setTransformerReactivePowerControlOn(true);
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
        assertTrue(result.isOk());
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