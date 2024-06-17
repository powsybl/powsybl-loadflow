/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
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
import com.powsybl.openloadflow.network.AutomationSystemNetworkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.util.LoadFlowAssert.assertCurrentEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class AutomationSystemTest {

    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setSimulateAutomationSystems(true);
    }

    @Test
    void testSwitchTripping() {
        Network network = AutomationSystemNetworkFactory.create();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        assertCurrentEquals(298.953, l12.getTerminal1());
        assertCurrentEquals(34.333, l34.getTerminal1()); // no more loop in LV network
        assertTrue(network.getSwitch("br1").isOpen());
    }

    @Test
    void testSwitchTripping2() {
        Network network = AutomationSystemNetworkFactory.createWithSwitchToClose();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        assertCurrentEquals(175.408, l12.getTerminal1());
        assertCurrentEquals(378.417, l34.getTerminal1());
        assertFalse(network.getSwitch("br1").isOpen());
    }

    @Test
    void testBranchTripping() {
        Network network = AutomationSystemNetworkFactory.createWithBranchTripping();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        Line l33p = network.getLine("l33p");
        assertCurrentEquals(298.973, l12.getTerminal1());
        assertCurrentEquals(298.973, l12.getTerminal2());
        assertCurrentEquals(34.448, l34.getTerminal1());
        assertTrue(Double.isNaN(l33p.getTerminal1().getI()));
        assertTrue(Double.isNaN(l33p.getTerminal2().getI()));
        // both sides are disconnected after simulation and not only side two as expected.
        assertFalse(l33p.getTerminal1().isConnected());
        assertFalse(l33p.getTerminal2().isConnected());
    }

    @Test
    void testBranchTripping2() {
        Network network = AutomationSystemNetworkFactory.createWithBranchTripping2();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        Line l33p = network.getLine("l33p");
        assertCurrentEquals(207.012, l12.getTerminal1());
        assertCurrentEquals(272.484, l34.getTerminal1());
        assertCurrentEquals(305.65, l33p.getTerminal1());
        // both sides are re-connected after simulation and not only side two as expected.
        assertTrue(l33p.getTerminal1().isConnected());
        assertTrue(l33p.getTerminal2().isConnected());
    }

    @Test
    void testNoTripping() {
        Network network = AutomationSystemNetworkFactory.createWithBadAutomationSystems();
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_l12")
                .setEnabled(true)
                .setMonitoredElementId("l56")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("l33p key")
                .setBranchToOperateId("l33p")
                .setSideToOperate(TwoSides.TWO)
                .setCurrentLimit(200.)
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        assertCurrentEquals(207.012, l12.getTerminal1());
        assertCurrentEquals(272.485, l34.getTerminal1());
        assertTrue(network.getLine("l33p").getTerminal1().isConnected());
        assertTrue(network.getLine("l33p").getTerminal2().isConnected());
    }

    @Test
    void testNoTripping2() {
        Network network = AutomationSystemNetworkFactory.createWithBadAutomationSystems();
        Substation s1 = network.getSubstation("s1");
        s1.newOverloadManagementSystem()
                .setId("l34_opens_l12")
                .setEnabled(true)
                .setMonitoredElementId("l34")
                .setMonitoredElementSide(ThreeSides.ONE)
                .newBranchTripping()
                .setKey("key")
                .setBranchToOperateId("l56")
                .setSideToOperate(TwoSides.TWO)
                .setCurrentLimit(200.)
                .add()
                .add();

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        Line l12 = network.getLine("l12");
        Line l34 = network.getLine("l34");
        assertCurrentEquals(207.012, l12.getTerminal1());
        assertCurrentEquals(272.485, l34.getTerminal1());
        assertTrue(network.getLine("l33p").getTerminal1().isConnected());
        assertTrue(network.getLine("l33p").getTerminal2().isConnected());
    }
}
