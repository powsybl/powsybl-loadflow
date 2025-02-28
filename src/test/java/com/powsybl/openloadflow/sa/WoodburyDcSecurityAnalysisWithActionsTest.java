/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.*;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.*;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.PhaseControlFactory;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.network.VoltageControlNetworkFactory;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.condition.TrueCondition;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.OperatorStrategyResult;
import com.powsybl.security.strategy.OperatorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Pierre Arvy {@literal <pierre.arvy at artelys.com>}
 */
class WoodburyDcSecurityAnalysisWithActionsTest extends AbstractOpenSecurityAnalysisTest {

    private LoadFlowParameters parameters;
    private SecurityAnalysisParameters securityAnalysisParameters;

    @BeforeEach
    void setUpWoodburyDcSa() {
        securityAnalysisParameters = new SecurityAnalysisParameters();
        // configure sa to use Woodbury dc sa
        parameters = new LoadFlowParameters();
        parameters.setDc(true);
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testFastSaDcPhaseTapChangerTapPositionChangeAdmittanceOnlyAlphaNull(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        // no alpha modification with pst actions
        network.getTwoWindingsTransformer("PS1")
                .getPhaseTapChanger()
                .getStep(0)
                .setAlpha(0);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "PS1", false, 0),
                new PhaseTapChangerTapPositionAction("pstRelChange", "PS1", true, -1));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstAbsChange")),
                new OperatorStrategy("strategyTapRelChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstRelChange")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbsL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        OperatorStrategyResult resultRel = getOperatorStrategyResult(result, "strategyTapRelChange");
        BranchResult brRelL2 = resultRel.getNetworkResult().getBranchResult("L2");
        BranchResult brRelPS1 = resultRel.getNetworkResult().getBranchResult("PS1");

        // Apply contingency by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L2
        assertEquals(network.getLine("L2").getTerminal1().getP(), brAbsL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brAbsL2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal1().getP(), brRelL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brRelL2.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brRelPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brRelPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testFastSaDcPhaseTapChangerTapPositionChangeAdmittanceOnlyAlphaNonNull(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        // no alpha modification with pst actions
        network.getTwoWindingsTransformer("PS1")
                .getPhaseTapChanger()
                .getStep(1)
                .setAlpha(-5);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "PS1", false, 0),
                new PhaseTapChangerTapPositionAction("pstRelChange", "PS1", true, -1));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstAbsChange")),
                new OperatorStrategy("strategyTapRelChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstRelChange")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbsL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        OperatorStrategyResult resultRel = getOperatorStrategyResult(result, "strategyTapRelChange");
        BranchResult brRelL2 = resultRel.getNetworkResult().getBranchResult("L2");
        BranchResult brRelPS1 = resultRel.getNetworkResult().getBranchResult("PS1");

        // Apply contingency by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L2
        assertEquals(network.getLine("L2").getTerminal1().getP(), brAbsL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brAbsL2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal1().getP(), brRelL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brRelL2.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brRelPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brRelPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaDcPhaseTapChangerTapPositionChangeAlphaOnly(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        network.getTwoWindingsTransformer("PS1")
                .getPhaseTapChanger()
                .getStep(0)
                .setX(100);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "PS1", false, 0),
                new PhaseTapChangerTapPositionAction("pstRelChange", "PS1", true, -1));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstAbsChange")),
                new OperatorStrategy("strategyTapRelChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstRelChange")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbsL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        OperatorStrategyResult resultRel = getOperatorStrategyResult(result, "strategyTapRelChange");
        BranchResult brRelL2 = resultRel.getNetworkResult().getBranchResult("L2");
        BranchResult brRelPS1 = resultRel.getNetworkResult().getBranchResult("PS1");

        // Apply contingency by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L2
        assertEquals(network.getLine("L2").getTerminal1().getP(), brAbsL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brAbsL2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal1().getP(), brRelL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brRelL2.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brRelPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brRelPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    // TODO Does not work in DC mode - to fix in a separate PR
    @Test
    void testSaDcPhaseTapChangerTapPositionChangeWithConnectivityBreak() {
        Network network = PhaseControlFactory.createNetworkWith3Buses();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L23", new BranchContingency("L23")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "PS1", false, 0),
                new PhaseTapChangerTapPositionAction("pstRelChange", "PS1", true, -1));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("L23"), new TrueCondition(), List.of("pstAbsChange")),
                new OperatorStrategy("strategyTapRelChange", ContingencyContext.specificContingency("L23"), new TrueCondition(), List.of("pstRelChange")));

        OpenLoadFlowParameters.create(parameters)
                .setSlackBusId("VL2_0")
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");
        BranchResult brAbsL12 = resultAbs.getNetworkResult().getBranchResult("L12");

        OperatorStrategyResult resultRel = getOperatorStrategyResult(result, "strategyTapRelChange");
        BranchResult brRelPS1 = resultRel.getNetworkResult().getBranchResult("PS1");
        BranchResult brRelL12 = resultAbs.getNetworkResult().getBranchResult("L12");

        // Apply contingency by hand
        network.getLine("L23").getTerminal1().disconnect();
        network.getLine("L23").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L12
        assertEquals(network.getLine("L12").getTerminal1().getP(), brAbsL12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L12").getTerminal2().getP(), brAbsL12.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L12").getTerminal1().getP(), brRelL12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L12").getTerminal2().getP(), brRelL12.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brRelPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brRelPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaDcPhaseTapChangerTapPositionChange(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "PS1", false, 0));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pstAbsChange")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbsL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        // Apply contingency by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L2
        assertEquals(network.getLine("L2").getTerminal1().getP(), brAbsL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brAbsL2.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testFastSaDcOneContingencyTwoTapPositionChange(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithTwoT2wtTwoLines();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pst1Change", "PS1", false, 0),
                new PhaseTapChangerTapPositionAction("pst2Change", "PS2", false, 2));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapChange", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pst1Change", "pst2Change")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapChange");
        BranchResult brL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");
        BranchResult brPS2 = resultAbs.getNetworkResult().getBranchResult("PS2");

        // Apply contingency by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        // Apply remedial actions
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);
        network.getTwoWindingsTransformer("PS2").getPhaseTapChanger().setTapPosition(2);

        loadFlowRunner.run(network, parameters);

        // Compare results on remaining branches
        assertEquals(network.getLine("L2").getTerminal1().getP(), brL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brL2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brPS1.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS2").getTerminal1().getP(), brPS2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS2").getTerminal2().getP(), brPS2.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testFastSaDcTwoContingenciesOneTapPositionChange(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithTwoT2wtTwoLines();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1+PS2", List.of(new BranchContingency("L1"), new BranchContingency("PS2"))));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pst1Change", "PS1", false, 0));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyTapChange", ContingencyContext.specificContingency("L1+PS2"), new TrueCondition(), List.of("pst1Change")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapChange");
        BranchResult brL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        // Apply contingencies by hand
        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        network.getTwoWindingsTransformer("PS2").getTerminal1().disconnect();
        network.getTwoWindingsTransformer("PS2").getTerminal2().disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on remaining branches
        assertEquals(network.getLine("L2").getTerminal1().getP(), brL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brL2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testFastSaDcN2ContingencyOneTapPositionChange(boolean dcFastMode) {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        // add load which will be lost by contingency
        network.getVoltageLevel("VL2").newLoad()
                .setId("LD3")
                .setConnectableBus("B2")
                .setBus("B2")
                .setP0(50.0)
                .setQ0(25.0)
                .add();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("L1+LD3", List.of(new BranchContingency("L1"), new LoadContingency("LD3"))));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstChange", "PS1", false, 0));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyTapChange", ContingencyContext.specificContingency("L1+LD3"), new TrueCondition(), List.of("pstChange")));

        securityAnalysisParameters.getExtension(OpenSecurityAnalysisParameters.class)
                .setDcFastMode(dcFastMode);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapChange");
        BranchResult brAbsL2 = resultAbs.getNetworkResult().getBranchResult("L2");
        BranchResult brAbsPS1 = resultAbs.getNetworkResult().getBranchResult("PS1");

        // Apply contingencies by hand
        network.getLine("L1").disconnect();
        network.getLoad("LD3").disconnect();
        // Apply remedial action
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(0);

        loadFlowRunner.run(network, parameters);

        // Compare results on the line L2
        assertEquals(network.getLine("L2").getTerminal1().getP(), brAbsL2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), brAbsL2.getP2(), LoadFlowAssert.DELTA_POWER);
        // Compare results on the t2wt PS1
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal1().getP(), brAbsPS1.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("PS1").getTerminal2().getP(), brAbsPS1.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastDcSaWithUnsupportedAction() {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("contingencyLD2", List.of(new LoadContingency("LD2"))));
        List<Action> actions = List.of(new GeneratorActionBuilder().withId("genActionG1").withGeneratorId("G1").withActivePowerRelativeValue(true).withActivePowerValue(1).build());
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyTapChange", ContingencyContext.specificContingency("contingencyLD2"), new TrueCondition(), List.of("genActionG1")));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP));
        assertTrue(thrown.getCause().getMessage().contains("For now, only PhaseTapChangerTapPositionAction, TerminalsConnectionAction and SwitchAction are allowed in WoodburyDcSecurityAnalysis"));
    }

    @Test
    void testFastDcSaWithTransformerEnabled() {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new TerminalsConnectionAction("closePS1", "PS1", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyClosePS1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("closePS1")));
        List<StateMonitor> monitors = List.of();

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP));
        assertTrue(thrown.getCause().getMessage().contains("For now, TerminalsConnectionAction enabling a transformer is not allowed in WoodburyDcSecurityAnalysis"));
    }

    @Test
    void testFastSaDcTransformerDisconnectionAction() {
        Network network = PhaseControlFactory.createWithOneT2wtTwoLines();
        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new TerminalsConnectionAction("openPS1", "PS1", true));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyOpenPS1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("openPS1")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        // Verify pst disconnection is well handled in Woodbury computation, when alpha of opened pst is null
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        assertEquals(100.0, getOperatorStrategyResult(result, "strategyOpenPS1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);

        // Same when alpha of opened pst is not null
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(2);
        result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP);
        assertEquals(100.0, getOperatorStrategyResult(result, "strategyOpenPS1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaDcActionBreakingConnectivityByOpeningLine() {
        Network network = PhaseControlFactory.createNetworkWith3Buses();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        // contingency does not break connectivity
        List<Contingency> contingencies = List.of(new Contingency("PS1", new BranchContingency("PS1")));
        // action does break connectivity
        List<Action> actions = List.of(new TerminalsConnectionAction("openL23", "L23", true));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyOpenL23", ContingencyContext.specificContingency("PS1"), new TrueCondition(), List.of("openL23")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        assertEquals(100.0, getOperatorStrategyResult(result, "strategyOpenL23").getNetworkResult().getBranchResult("L12").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaDcActionBreakingConnectivityByOpeningSwitchAndTransformer() {
        Network network = VoltageControlNetworkFactory.createNetworkWith2T2wtAndSwitch();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        // contingency does not break connectivity
        List<Contingency> contingencies = List.of(new Contingency("LOAD_3", new LoadContingency("LOAD_3")));
        // actions removes bus 3 from main connected component
        List<Action> actions = List.of(new TerminalsConnectionAction("openT2wT", "T2wT", true), new SwitchAction("openSWITCH", "SWITCH", true));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyOpen", ContingencyContext.specificContingency("LOAD_3"),
                new TrueCondition(), List.of("openT2wT", "openSWITCH")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategyOpen");
        BranchResult brL12 = operatorStrategyResult.getNetworkResult().getBranchResult("LINE_12");
        BranchResult brT2wT2 = operatorStrategyResult.getNetworkResult().getBranchResult("T2wT2");
        BranchResult brL15 = operatorStrategyResult.getNetworkResult().getBranchResult("LINE_15");

        // Apply contingency/remedial action by hand and run LF
        network.getLoad("LOAD_3").disconnect();
        network.getTwoWindingsTransformer("T2wT").disconnect();
        network.getSwitch("SWITCH").setOpen(true);
        loadFlowRunner.run(network, parameters);

        // Compare results of DC SA and LF
        assertEquals(network.getLine("LINE_12").getTerminal1().getP(), brL12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("LINE_12").getTerminal2().getP(), brL12.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("T2wT2").getTerminal1().getP(), brT2wT2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("T2wT2").getTerminal2().getP(), brT2wT2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("LINE_15").getTerminal1().getP(), brL15.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("LINE_15").getTerminal2().getP(), brL15.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaDcActionBreakingConnectivityWithContingencyOnly() {
        Network network = VoltageControlNetworkFactory.createNetworkWith2T2wtAndSwitch();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("T2wT2+GEN_5", new BranchContingency("T2wT2"), new GeneratorContingency("GEN_5")));
        // action will break connectivity when it is applied with the contingency
        List<Action> actions = List.of(new TerminalsConnectionAction("openLINE_12", "LINE_12", true));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyOpenLINE_12", ContingencyContext.specificContingency("T2wT2+GEN_5"), new TrueCondition(), List.of("openLINE_12")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        assertEquals(2, getOperatorStrategyResult(result, "strategyOpenLINE_12").getNetworkResult().getBranchResult("LINE_15").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaDcActionsBreakingConnectivityAndChangingTapPosition() {
        Network network = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        // add pst on tr2 to modify its tap
        network.getTwoWindingsTransformer("tr2").newPhaseTapChanger()
                .setTapPosition(1)
                .setRegulationTerminal(network.getTwoWindingsTransformer("tr2").getTerminal2())
                .setRegulationMode(PhaseTapChanger.RegulationMode.FIXED_TAP)
                .setRegulationValue(200)
                .beginStep()
                .setAlpha(-5)
                .setRho(1.0)
                .setR(0.0)
                .setX(50)
                .setG(0.0)
                .setB(0.0)
                .endStep()
                .beginStep()
                .setAlpha(0.0)
                .setRho(1.0)
                .setR(0.0)
                .setX(100)
                .setG(0.0)
                .setB(0.0)
                .endStep()
                .add();
        // add parallel line to the pst to verify flows repartition
        network.newLine()
                .setId("l42")
                .setBus1("b4")
                .setBus2("b2")
                .setR(0)
                .setX(100.0)
                .add();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        // contingency breaks connectivity
        List<Contingency> contingencies = List.of(new Contingency("tr3", new BranchContingency("tr3")));
        // actions break connectivity and modify tap position
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("changeTr2", "tr2", false, 0),
                new TerminalsConnectionAction("openTr1", "tr1", true));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy2Actions", ContingencyContext.specificContingency("tr3"),
                new TrueCondition(), List.of("openTr1", "changeTr2")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy2Actions");
        BranchResult brTr2 = operatorStrategyResult.getNetworkResult().getBranchResult("tr2");
        BranchResult brL42 = operatorStrategyResult.getNetworkResult().getBranchResult("l42");

        // Apply contingency/action by hand and run LF
        network.getTwoWindingsTransformer("tr1").disconnect();
        network.getTwoWindingsTransformer("tr2").getPhaseTapChanger().setTapPosition(0);
        network.getTwoWindingsTransformer("tr3").disconnect();
        loadFlowRunner.run(network, parameters);

        // Compare results of DC SA and LF
        assertEquals(network.getTwoWindingsTransformer("tr2").getTerminal1().getP(), brTr2.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("tr2").getTerminal2().getP(), brTr2.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l42").getTerminal1().getP(), brL42.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l42").getTerminal2().getP(), brL42.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaDcActionRestoringConnectivityByClosingLine() {
        Network network = PhaseControlFactory.createNetworkWith3Buses();
        // open L12 to restore connectivity by closing it
        network.getLine("L12").disconnect();

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("PS1", new BranchContingency("PS1")));
        List<Action> actions = List.of(new TerminalsConnectionAction("closeL12", "L12", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyCloseL12", ContingencyContext.specificContingency("PS1"), new TrueCondition(), List.of("closeL12")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        assertNotNull(result);
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategyCloseL12");
        BranchResult brL12 = operatorStrategyResult.getNetworkResult().getBranchResult("L12");
        BranchResult brL23 = operatorStrategyResult.getNetworkResult().getBranchResult("L23");

        // Apply contingency/action by hand and run LF
        network.getTwoWindingsTransformer("PS1").disconnect();
        network.getLine("L12").connect();
        loadFlowRunner.run(network, parameters);

        // Compare results of fast DC SA and LF
        assertEquals(network.getLine("L12").getTerminal1().getP(), brL12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L12").getTerminal2().getP(), brL12.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L23").getTerminal1().getP(), brL23.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L23").getTerminal2().getP(), brL23.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testFastSaActionRestoringConnectivityByClosingSwitch() {
        Network network = VoltageControlNetworkFactory.createNetworkWith2T2wtAndSwitch();
        // open switch to restore connectivity by closing it
        network.getSwitch("SWITCH").setOpen(true);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("LINE_15+GEN_5", new BranchContingency("LINE_15"), new GeneratorContingency("GEN_5")));
        List<Action> actions = List.of(new SwitchAction("closeSWITCH", "SWITCH", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyCloseSWITCH", ContingencyContext.specificContingency("LINE_15+GEN_5"), new TrueCondition(), List.of("closeSWITCH")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, ReportNode.NO_OP);
        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyCloseSWITCH");
        BranchResult brL12 = resultAbs.getNetworkResult().getBranchResult("LINE_12");
        BranchResult brT2wT = resultAbs.getNetworkResult().getBranchResult("T2wT");
        BranchResult brT2wT2 = resultAbs.getNetworkResult().getBranchResult("T2wT2");

        // Apply contingency/action by hand and run LF
        network.getLine("LINE_15").disconnect();
        network.getGenerator("GEN_5").disconnect();
        network.getSwitch("SWITCH").setOpen(false);
        loadFlowRunner.run(network, parameters);

        // Compare results of fast DC SA and LF
        assertEquals(network.getLine("LINE_12").getTerminal1().getP(), brL12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("T2wT").getTerminal1().getP(), brT2wT.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getTwoWindingsTransformer("T2wT2").getTerminal1().getP(), brT2wT2.getP1(), LoadFlowAssert.DELTA_POWER);
    }
}
