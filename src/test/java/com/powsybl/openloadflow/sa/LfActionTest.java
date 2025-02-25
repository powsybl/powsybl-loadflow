/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.*;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.test.AbstractSerDeTest;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.LoadContingency;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.action.*;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 * @author Jean-Luc Bouchot {@literal <jlbouchot at gmail.com>}
 */
class LfActionTest extends AbstractSerDeTest {

    @Override
    @BeforeEach
    public void setUp() throws IOException {
        super.setUp();
    }

    @Override
    @AfterEach
    public void tearDown() throws IOException {
        super.tearDown();
    }

    @Test
    void test() {
        Network network = NodeBreakerNetworkFactory.create();
        SwitchAction switchAction = new SwitchAction("switchAction", "C", true);
        var matrixFactory = new DenseMatrixFactory();
        LoadFlowParameters loadFlowParameters = new LoadFlowParameters();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network,
                loadFlowParameters, new OpenLoadFlowParameters(), matrixFactory, new NaiveGraphConnectivityFactory<>(LfBus::getNum), true, false);
        LfTopoConfig topoConfig = new LfTopoConfig();
        LfNetworkParameters networkParameters = acParameters.getNetworkParameters();
        topoConfig.getSwitchesToOpen().add(network.getSwitch("C"));
        try (LfNetworkList lfNetworks = Networks.load(network, networkParameters, topoConfig, ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();
            LfAction lfSwitchAction = LfActionUtils.createLfAction(switchAction, network, networkParameters.isBreakers(), lfNetwork);
            String loadId = "LOAD";
            Contingency contingency = new Contingency(loadId, new LoadContingency("LD"));
            PropagatedContingencyCreationParameters creationParameters = new PropagatedContingencyCreationParameters()
                    .setHvdcAcEmulation(false);
            PropagatedContingency propagatedContingency = PropagatedContingency.createList(network,
                    Collections.singletonList(contingency), new LfTopoConfig(), creationParameters).get(0);
            propagatedContingency.toLfContingency(lfNetwork).ifPresent(lfContingency -> {
                lfSwitchAction.apply(lfNetwork, lfContingency, networkParameters);
                assertTrue(lfNetwork.getBranchById("C").isDisabled());
                assertEquals("C", ((LfSwitchAction) lfSwitchAction).getDisabledBranch().getId());
                assertNull(((LfSwitchAction) lfSwitchAction).getEnabledBranch());
            });

            LfAction lfInvalidSwitchAction = LfActionUtils.createLfAction(new SwitchAction("switchAction", "S", true),
                network, networkParameters.isBreakers(), lfNetwork);
            LfAction lfInvalidTerminalsConnectionAction = LfActionUtils.createLfAction(new TerminalsConnectionAction("A line action", "x", true),
                network, networkParameters.isBreakers(), lfNetwork);
            LfAction lfInvalidPhaseTapChangerTapPositionAction = LfActionUtils.createLfAction(new PhaseTapChangerTapPositionAction("A phase tap change action", "y", false, 3),
                network, networkParameters.isBreakers(), lfNetwork);

            assertFalse(lfInvalidSwitchAction.apply(lfNetwork, null, networkParameters));
            assertFalse(lfInvalidTerminalsConnectionAction.apply(lfNetwork, null, networkParameters));
            assertFalse(lfInvalidPhaseTapChangerTapPositionAction.apply(lfNetwork, null, networkParameters));

            var lineAction = new TerminalsConnectionAction("A line action", "L1", ThreeSides.ONE, false);
            assertEquals("Terminals connection action: only open or close branch at both sides is supported yet.",
                assertThrows(UnsupportedOperationException.class, () -> new LfTerminalsConnectionAction("A line action", lineAction, lfNetwork)).getMessage());
        }
    }

    @Test
    void testUnsupportedGeneratorAction() {
        Network network = NodeBreakerNetworkFactory.create();
        String genId = "G";
        GeneratorAction generatorAction = new GeneratorActionBuilder()
                .withId("genAction" + genId)
                .withGeneratorId(genId)
                .withTargetQ(100) // to be done soon
                .build();
        var matrixFactory = new DenseMatrixFactory();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network,
                new LoadFlowParameters(), new OpenLoadFlowParameters(), matrixFactory, new NaiveGraphConnectivityFactory<>(LfBus::getNum), true, false);
        try (LfNetworkList lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), new LfTopoConfig(), ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> new LfGeneratorAction("Gen action", generatorAction, lfNetwork));
            assertEquals("Generator action on G : configuration not supported yet.", e.getMessage());
        }
    }

    @Test
    void testGeneratorActionWithRelativeActivePowerValue() {
        Network network = NodeBreakerNetworkFactory.create();
        String genId = "G";
        Generator generator = network.getGenerator(genId);
        final double deltaTargetP = 2d;
        final double oldTargetP = generator.getTargetP();
        final double newTargetP = oldTargetP + deltaTargetP;
        GeneratorAction generatorAction = new GeneratorActionBuilder()
                .withId("genAction_" + genId)
                .withGeneratorId(genId)
                .withActivePowerRelativeValue(true)
                .withActivePowerValue(deltaTargetP)
                .build();
        var matrixFactory = new DenseMatrixFactory();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network,
                new LoadFlowParameters(), new OpenLoadFlowParameters(), matrixFactory, new NaiveGraphConnectivityFactory<>(LfBus::getNum), true, false);
        try (LfNetworkList lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), new LfTopoConfig(), ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();
            LfAction lfAction = LfActionUtils.createLfAction(generatorAction, network, acParameters.getNetworkParameters().isBreakers(), lfNetwork);
            lfAction.apply(lfNetwork, null, acParameters.getNetworkParameters());
            assertEquals(newTargetP / PerUnit.SB, lfNetwork.getGeneratorById(genId).getTargetP());
            assertEquals(genId, generatorAction.getGeneratorId());
            assertEquals(oldTargetP, network.getGenerator(genId).getTargetP());
        }
    }

    @Test
    void testHvdcAction() {
        // the hvc line is operated in AC emulation before applying the action. A change in P0 and droop is not supported yet.
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();
        HvdcAction hvdcAction = new HvdcActionBuilder()
                .withId("action")
                .withHvdcId("hvdc34")
                .withAcEmulationEnabled(true)
                .withP0(200.0)
                .withDroop(90.0)
                .build();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> new LfHvdcAction("action", hvdcAction));
        assertEquals("Hvdc action: enabling ac emulation mode through an action is not supported yet.", e.getMessage());
    }

    @Test
    void testHvdcAction2() {
        // This action is valid but AC emulation disabling has not been explicitly set, not supported.
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        HvdcAction hvdcAction2 = new HvdcActionBuilder()
            .withId("action")
            .withHvdcId("hvdc34")
            .withP0(200.0)
            .withDroop(90.0)
            .build();
        var matrixFactory = new DenseMatrixFactory();
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network,
            new LoadFlowParameters(), new OpenLoadFlowParameters(), matrixFactory, new NaiveGraphConnectivityFactory<>(LfBus::getNum), true, false);
        try (LfNetworkList lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), new LfTopoConfig(), ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();
            LfAction lfAction = LfActionUtils.createLfAction(hvdcAction2, network, acParameters.getNetworkParameters().isBreakers(), lfNetwork);
            assertFalse(lfAction.apply(lfNetwork, null, acParameters.getNetworkParameters()));
        }
    }

    @Test
    void testLfAreaInterchangeTargetAction() {

        AreaInterchangeTargetAction targetAction = new AreaInterchangeTargetActionBuilder()
            .withId("action")
            .withAreaId("a1")
            .withTarget(20.0)
            .build();

        AreaInterchangeTargetAction invalidTargetAction = new AreaInterchangeTargetActionBuilder()
            .withId("action")
            .withAreaId("DUMMMY")
            .withTarget(20.0)
            .build();

        Network network = MultiAreaNetworkFactory.createTwoAreasWithXNode();

        var matrixFactory = new DenseMatrixFactory();

        // With area interchange target control enabled
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network,
            new LoadFlowParameters(), new OpenLoadFlowParameters(), matrixFactory, new NaiveGraphConnectivityFactory<>(LfBus::getNum), true, false);
        acParameters.getNetworkParameters().setAreaInterchangeControl(true);
        try (LfNetworkList lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), new LfTopoConfig(), ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();

            LfAction lfAreaTargetAction = LfActionUtils.createLfAction(targetAction, network, acParameters.getNetworkParameters().isBreakers(), lfNetwork);
            assertTrue(lfAreaTargetAction.apply(lfNetwork, null, acParameters.getNetworkParameters()));

            LfAction lfAreaTargetAction2 = LfActionUtils.createLfAction(invalidTargetAction, network, acParameters.getNetworkParameters().isBreakers(), lfNetwork);
            assertFalse(lfAreaTargetAction2.apply(lfNetwork, null, acParameters.getNetworkParameters()));
        }

        // With area interchange target control disabled
        acParameters.getNetworkParameters().setAreaInterchangeControl(false);
        try (LfNetworkList lfNetworks = Networks.load(network, acParameters.getNetworkParameters(), new LfTopoConfig(), ReportNode.NO_OP)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow();
            acParameters.getNetworkParameters().setAreaInterchangeControl(false);

            LfAction lfAreaTargetAction = LfActionUtils.createLfAction(targetAction, network, acParameters.getNetworkParameters().isBreakers(), lfNetwork);
            assertFalse(lfAreaTargetAction.apply(lfNetwork, null, acParameters.getNetworkParameters()));
        }
    }
}
