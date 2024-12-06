/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.google.common.collect.ImmutableList;
import com.powsybl.action.Action;
import com.powsybl.action.LoadActionBuilder;
import com.powsybl.action.TerminalsConnectionAction;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.*;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.criteria.AtLeastOneNominalVoltageCriterion;
import com.powsybl.iidm.criteria.IdentifiableCriterion;
import com.powsybl.iidm.criteria.VoltageInterval;
import com.powsybl.iidm.criteria.duration.IntervalTemporaryDurationCriterion;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.LoadDetailAdder;
import com.powsybl.iidm.network.extensions.StandbyAutomatonAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.iidm.network.test.SecurityAnalysisTestNetworkFactory;
import com.powsybl.iidm.network.util.LimitViolationUtils;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteriaType;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.OlfBranchResult;
import com.powsybl.openloadflow.network.impl.OlfThreeWindingsTransformerResult;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.security.*;
import com.powsybl.security.condition.TrueCondition;
import com.powsybl.security.limitreduction.LimitReduction;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.*;
import com.powsybl.security.strategy.OperatorStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class OpenSecurityAnalysisTest extends AbstractOpenSecurityAnalysisTest {

    @Test
    void testCurrentLimitViolations() {
        Network network = createNodeBreakerNetwork();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());
        contingencies.add(new Contingency("LD", new LoadContingency("LD")));

        StateMonitor stateMonitor = new StateMonitor(ContingencyContext.all(), Collections.emptySet(),
                network.getVoltageLevelStream().map(Identifiable::getId).collect(Collectors.toSet()), Collections.emptySet());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, List.of(stateMonitor), securityAnalysisParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(3, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "LD");
        assertEquals(398.0, postContingencyResult.getNetworkResult().getBusResult("BBS2").getV(), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testCurrentLimitViolations2() {
        Network network = createNodeBreakerNetwork();
        network.getLine("L1").getCurrentLimits1().ifPresent(limits -> limits.setPermanentLimit(200));

        List<Contingency> contingencies = List.of(new Contingency("L2", new BranchContingency("L2")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(1, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
    }

    @Test
    void testLowVoltageLimitViolations() {
        Network network = createNodeBreakerNetwork();
        network.getGenerator("G").setTargetV(393);

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(3, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());

        List<LimitViolation> limitViolations = result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations();
        Optional<LimitViolation> limitViolationL21 = limitViolations.stream().filter(limitViolation -> limitViolation.getSubjectId().equals("L2") && limitViolation.getSideAsTwoSides() == TwoSides.ONE).findFirst();
        assertTrue(limitViolationL21.isPresent());
        assertEquals(0, limitViolationL21.get().getAcceptableDuration());
        assertEquals(950, limitViolationL21.get().getLimit());
        Optional<LimitViolation> limitViolationL22 = limitViolations.stream().filter(limitViolation -> limitViolation.getSubjectId().equals("L2") && limitViolation.getSideAsTwoSides() == TwoSides.TWO).findFirst();
        assertTrue(limitViolationL22.isPresent());
        assertEquals(0, limitViolationL22.get().getAcceptableDuration());
        assertEquals(970, limitViolationL22.get().getLimit());

        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertEquals(3, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());

        List<LimitViolation> limitViolations1 = result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations();
        LimitViolation lowViolation = limitViolations1.get(2);
        assertEquals(LimitViolationType.LOW_VOLTAGE, lowViolation.getLimitType());
        assertEquals(370, lowViolation.getLimit());

        Optional<ViolationLocation> vl1ViolationLocation = lowViolation.getViolationLocation();
        assertTrue(vl1ViolationLocation.isPresent());
        assertEquals(ViolationLocation.Type.NODE_BREAKER, vl1ViolationLocation.get().getType());
        assertEquals(List.of(0, 1, 2, 3), ((NodeBreakerViolationLocation) vl1ViolationLocation.get()).getNodes());
    }

    @Test
    void testHighVoltageLimitViolations() {
        Network network = createNodeBreakerNetwork();
        network.getGenerator("G").setTargetV(421);

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(2, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());

        assertEquals(0, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertEquals(0, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());

        Optional<ViolationLocation> vl1ViolationLocation = result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().get(0).getViolationLocation();
        assertTrue(vl1ViolationLocation.isPresent());
        assertEquals(ViolationLocation.Type.NODE_BREAKER, vl1ViolationLocation.get().getType());
        assertEquals(List.of(0, 1, 3, 4, 5, 6), ((NodeBreakerViolationLocation) vl1ViolationLocation.get()).getNodes());
    }

    @Test
    void testActivePowerLimitViolations() {
        Network network = createNodeBreakerNetwork();
        network.getLine("L1").newActivePowerLimits1()
               .setPermanentLimit(1.0)
               .beginTemporaryLimit()
               .setName("60")
               .setAcceptableDuration(60)
               .setValue(1.2)
               .endTemporaryLimit()
               .add();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());

        LimitViolation limitViolation0 = result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().get(0);
        assertEquals("L1", limitViolation0.getSubjectId());
        assertEquals(LimitViolationType.ACTIVE_POWER, limitViolation0.getLimitType());
        assertEquals(608.334, limitViolation0.getValue(), 10E-3);

        int activePowerLimitViolationsCount = 0;
        for (PostContingencyResult r : result.getPostContingencyResults()) {
            for (LimitViolation v : r.getLimitViolationsResult().getLimitViolations()) {
                if (v.getLimitType() == LimitViolationType.ACTIVE_POWER) {
                    activePowerLimitViolationsCount++;
                }
            }
        }
        assertEquals(1, activePowerLimitViolationsCount);
    }

    @Test
    void testApparentPowerLimitViolations() {
        Network network = createNodeBreakerNetwork();
        network.getLine("L1").newApparentPowerLimits1()
               .setPermanentLimit(1.0)
               .beginTemporaryLimit()
               .setName("60")
               .setAcceptableDuration(60)
               .setValue(1.2)
               .endTemporaryLimit()
               .add();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());

        LimitViolation limitViolation0 = result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().get(0);
        assertEquals("L1", limitViolation0.getSubjectId());
        assertEquals(LimitViolationType.APPARENT_POWER, limitViolation0.getLimitType());
        assertEquals(651.796, limitViolation0.getValue(), 10E-3);

        int apparentPowerLimitViolationsCount = 0;
        for (PostContingencyResult r : result.getPostContingencyResults()) {
            for (LimitViolation v : r.getLimitViolationsResult().getLimitViolations()) {
                if (v.getLimitType() == LimitViolationType.APPARENT_POWER) {
                    apparentPowerLimitViolationsCount++;
                }
            }
        }
        assertEquals(1, apparentPowerLimitViolationsCount);
    }

    @Test
    void testFourSubstations() {
        Network network = FourSubstationsNodeBreakerFactory.create();

        // Testing all contingencies at once
        List<Contingency> contingencies = createAllBranchesContingencies(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());

        LoadFlowParameters loadFlowParameters = new LoadFlowParameters()
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        result = runSecurityAnalysis(network, contingencies, loadFlowParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
    }

    @Test
    void testNoGenerator() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getGenerator("GEN").getTerminal().disconnect();

        SecurityAnalysisResult result = runSecurityAnalysis(network);
        assertSame(LoadFlowResult.ComponentResult.Status.FAILED, result.getPreContingencyResult().getStatus());
    }

    @Test
    void testNoRemainingGenerator() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        List<Contingency> contingencies = List.of(new Contingency("NGEN_NHV1", new BranchContingency("NGEN_NHV1")));

        LoadFlowParameters parameters = new LoadFlowParameters();
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, parameters);

        assertSame(PostContingencyComputationStatus.SOLVER_FAILED, result.getPostContingencyResults().get(0).getStatus());
    }

    @Test
    void testNoRemainingLoad() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDistributedSlack(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("NHV2_NLOAD", new BranchContingency("NHV2_NLOAD")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
    }

    @Test
    void testSaWithSeveralConnectedComponents() {
        Network network = ConnectedComponentNetworkFactory.createTwoUnconnectedCC();

        // Testing all contingencies at once
        List<Contingency> contingencies = createAllBranchesContingencies(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
    }

    @Test
    void testSaWithStateMonitor() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        // 2 N-1 on the 2 lines
        List<Contingency> contingencies = List.of(
            new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
            new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2"))
        );

        // Monitor on branch and step-up transformer for all states
        List<StateMonitor> monitors = List.of(
            new StateMonitor(ContingencyContext.all(), Set.of("NHV1_NHV2_1", "NGEN_NHV1"), Set.of("VLLOAD"), emptySet())
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        List<BusResult> busResults = preContingencyResult.getNetworkResult().getBusResults();
        BusResult expectedBus = new BusResult("VLLOAD", "NLOAD", 147.6, -9.6);
        assertEquals(1, busResults.size());
        assertAlmostEquals(expectedBus, busResults.get(0), 0.1);

        assertEquals(2, preContingencyResult.getNetworkResult().getBranchResults().size());
        assertAlmostEquals(new BranchResult("NHV1_NHV2_1", 302, 99, 457, -300, -137.2, 489),
                           preContingencyResult.getNetworkResult().getBranchResult("NHV1_NHV2_1"), 1);
        assertAlmostEquals(new BranchResult("NGEN_NHV1", 606, 225, 15226, -605, -198, 914),
                           preContingencyResult.getNetworkResult().getBranchResult("NGEN_NHV1"), 1);

        //No result when the branch itself is disconnected
        assertNull(result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("NHV1_NHV2_1"));

        assertAlmostEquals(new BranchResult("NHV1_NHV2_1", 611, 334, 1009, -601, -285, 1048),
                result.getPostContingencyResults().get(1).getNetworkResult().getBranchResult("NHV1_NHV2_1"), 1);
        assertAlmostEquals(new BranchResult("NGEN_NHV1", 611, 368, 16815, -611, -334, 1009),
                           result.getPostContingencyResults().get(1).getNetworkResult().getBranchResult("NGEN_NHV1"), 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaWithStateMonitorPreContingency(boolean dcFastMode) {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        // 2 N-1 on the 2 lines
        List<Contingency> contingencies = List.of(
                new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
                new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2"))
        );

        // Monitor on branch and step-up transformer for all states
        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.none(), Set.of("NHV1_NHV2_1", "NGEN_NHV1"), Set.of("VLLOAD"), emptySet())
        );

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDc(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(2, preContingencyResult.getNetworkResult().getBranchResults().size());
    }

    @Test
    void testSaWithStateMonitorNotExistingBranchBus() {
        Network network = DistributedSlackNetworkFactory.create();

        List<StateMonitor> monitors = List.of(
            new StateMonitor(ContingencyContext.all(), Collections.singleton("l1"), Collections.singleton("bus"), Collections.singleton("three windings"))
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(0, result.getPreContingencyResult().getNetworkResult().getBusResults().size());
        assertEquals(0, result.getPreContingencyResult().getNetworkResult().getBranchResults().size());
    }

    @Test
    void testSaWithStateMonitorDisconnectBranch() {
        Network network = DistributedSlackNetworkFactory.create();
        network.getGenerator("g1").setMaxP(1000);
        network.getGenerator("g2").setMaxP(1000);
        network.getBranch("l34").getTerminal1().disconnect();

        List<StateMonitor> monitors = new ArrayList<>();
        monitors.add(new StateMonitor(ContingencyContext.all(), Collections.singleton("l34"), Collections.singleton("b1_vl"), emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, Collections.emptyList(), monitors);

        assertEquals(1, result.getPreContingencyResult().getNetworkResult().getBusResults().size());

        assertEquals(new BusResult("b1_vl", "b1", 400, 0.007162421348571367), result.getPreContingencyResult().getNetworkResult().getBusResults().get(0));
        assertEquals(1, result.getPreContingencyResult().getNetworkResult().getBranchResults().size());
        assertEquals(new BranchResult("l34", 0, 0, 0, 0.0, -0.0, 0.0),
                     result.getPreContingencyResult().getNetworkResult().getBranchResults().get(0));

        network = DistributedSlackNetworkFactory.create();
        network.getGenerator("g1").setMaxP(1000);
        network.getGenerator("g2").setMaxP(1000);
        network.getBranch("l34").getTerminal2().disconnect();

        result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);
        assertEquals(0, result.getPreContingencyResult().getNetworkResult().getBranchResults().size());

        network = DistributedSlackNetworkFactory.create();
        network.getGenerator("g1").setMaxP(1000);
        network.getGenerator("g2").setMaxP(1000);
        network.getBranch("l34").getTerminal2().disconnect();
        network.getBranch("l34").getTerminal1().disconnect();

        result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);
        assertEquals(0, result.getPreContingencyResult().getNetworkResult().getBranchResults().size());
    }

    @Test
    void testSaWithStateMonitorDanglingLine() {
        Network network = BoundaryFactory.createWithLoad();
        List<StateMonitor> monitors = new ArrayList<>();
        monitors.add(new StateMonitor(ContingencyContext.all(), Collections.singleton("dl1"), Collections.singleton("vl1"), emptySet()));
        List<Contingency> contingencies = List.of(new Contingency("contingency", new LoadContingency("load3")));
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setSlackBusPMaxMismatch(0.01);
        securityAnalysisParameters.getLoadFlowParameters().addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                Collections.emptyList(), Collections.emptyList(), ReportNode.NO_OP);
        BranchResult preContingencyBranchResult = result.getPreContingencyResult().getNetworkResult().getBranchResult("dl1");
        assertEquals(Double.NaN, preContingencyBranchResult.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);
        assertEquals(91.294, preContingencyBranchResult.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-91.000, preContingencyBranchResult.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(260.511, preContingencyBranchResult.getI1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(260.970, preContingencyBranchResult.getI2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(149.751, preContingencyBranchResult.getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-150.000, preContingencyBranchResult.getQ2(), LoadFlowAssert.DELTA_POWER);
        BranchResult postContingencyBranchResult = getPostContingencyResult(result, "contingency").getNetworkResult().getBranchResult("dl1");
        assertEquals(Double.NaN, postContingencyBranchResult.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);
        assertEquals(91.294, postContingencyBranchResult.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-91.000, postContingencyBranchResult.getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(260.488, postContingencyBranchResult.getI1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(260.947, postContingencyBranchResult.getI2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(149.751, postContingencyBranchResult.getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-150.000, postContingencyBranchResult.getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithStateMonitorLfLeg() {
        Network network = T3wtFactory.create();
        List<Contingency> contingencies = network.getBranchStream()
                .limit(1)
                .map(b -> new Contingency(b.getId(), new BranchContingency(b.getId())))
                .collect(Collectors.toList());

        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
        parameters.setLoadFlowParameters(new LoadFlowParameters());
        parameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters().setCreateResultExtension(true));
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), emptySet(), emptySet(), Collections.singleton("3wt")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors, parameters);
        assertEquals(1, result.getPreContingencyResult().getNetworkResult().getThreeWindingsTransformerResults().size());
        assertAlmostEquals(new ThreeWindingsTransformerResult("3wt", 161, 82, 258,
                        -161, -74, 435, 0, 0, 0),
                result.getPreContingencyResult().getNetworkResult().getThreeWindingsTransformerResults().get(0), 1);
        OlfThreeWindingsTransformerResult twtExt =
                result.getPreContingencyResult().getNetworkResult().getThreeWindingsTransformerResults().get(0).getExtension(OlfThreeWindingsTransformerResult.class);
        assertEquals(405.0, twtExt.getV1(), DELTA_V);
        assertEquals(235.131, twtExt.getV2(), DELTA_V);
        assertEquals(20.834, twtExt.getV3(), DELTA_V);
        assertEquals(0.0, twtExt.getAngle1(), DELTA_ANGLE);
        assertEquals(-2.259241, twtExt.getAngle2(), DELTA_ANGLE);
        assertEquals(-2.721885, twtExt.getAngle3(), DELTA_ANGLE);

        network.getThreeWindingsTransformer("3wt").getLeg3().getTerminal().disconnect();
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors, parameters);
        assertEquals(1, result2.getPreContingencyResult().getNetworkResult().getThreeWindingsTransformerResults().size());
        assertAlmostEquals(new ThreeWindingsTransformerResult("3wt", 161, 82, 258,
                        -161, -74, 435, 0, 0, 0),
                result2.getPreContingencyResult().getNetworkResult().getThreeWindingsTransformerResults().get(0), 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaDcMode(boolean dcFastMode) {
        Network fourBusNetwork = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(fourBusNetwork);

        fourBusNetwork.getLine("l14").newActivePowerLimits1().setPermanentLimit(0.1).add();
        fourBusNetwork.getLine("l12").newActivePowerLimits1().setPermanentLimit(0.2).add();
        fourBusNetwork.getLine("l23").newActivePowerLimits1().setPermanentLimit(0.25).add();
        fourBusNetwork.getLine("l34").newActivePowerLimits1().setPermanentLimit(0.15).add();
        fourBusNetwork.getLine("l13").newActivePowerLimits1().setPermanentLimit(0.1).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(fourBusNetwork, contingencies, monitors, securityAnalysisParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(2).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(3).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(4).getLimitViolationsResult().getLimitViolations().size());

        //Branch result for first contingency
        assertEquals(4, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().size());

        //Check branch results for flowTransfer computation for contingency on l14
        PostContingencyResult postContl14 = getPostContingencyResult(result, "l14");
        assertEquals("l14", postContl14.getContingency().getId());

        BranchResult brl14l12 = postContl14.getNetworkResult().getBranchResult("l12");
        assertEquals(0.333, brl14l12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l12.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l23 = postContl14.getNetworkResult().getBranchResult("l23");
        assertEquals(1.333, brl14l23.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l23.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l34 = postContl14.getNetworkResult().getBranchResult("l34");
        assertEquals(-1.0, brl14l34.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.0, brl14l34.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l13 = postContl14.getNetworkResult().getBranchResult("l13");
        assertEquals(1.666, brl14l13.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.666, brl14l13.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaDcModeSpecificContingencies(boolean dcFastMode) {
        Network fourBusNetwork = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(fourBusNetwork);

        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12"), Collections.emptySet(), Collections.emptySet()),
                new StateMonitor(ContingencyContext.specificContingency("l14"), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(fourBusNetwork, contingencies, monitors, securityAnalysisParameters);

        assertEquals(2, result.getPreContingencyResult().getNetworkResult().getBranchResults().size());
        assertEquals("l12", result.getPreContingencyResult().getNetworkResult().getBranchResults().get(0).getBranchId());
        assertEquals("l14", result.getPreContingencyResult().getNetworkResult().getBranchResults().get(1).getBranchId());

        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(4, getPostContingencyResult(result, "l14").getNetworkResult().getBranchResults().size());
        assertEquals(1, getPostContingencyResult(result, "l12").getNetworkResult().getBranchResults().size());
        assertEquals(2, getPostContingencyResult(result, "l13").getNetworkResult().getBranchResults().size());
        assertEquals(2, getPostContingencyResult(result, "l34").getNetworkResult().getBranchResults().size());
        assertEquals(2, getPostContingencyResult(result, "l23").getNetworkResult().getBranchResults().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSaDcModeWithIncreasedParameters(boolean dcFastMode) {
        Network fourBusNetwork = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisParameters.IncreasedViolationsParameters increasedViolationsParameters = new SecurityAnalysisParameters.IncreasedViolationsParameters();
        increasedViolationsParameters.setFlowProportionalThreshold(0);
        securityAnalysisParameters.setIncreasedViolationsParameters(increasedViolationsParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(fourBusNetwork);

        fourBusNetwork.getLine("l14").newActivePowerLimits1().setPermanentLimit(0.1).add();
        fourBusNetwork.getLine("l12").newActivePowerLimits1().setPermanentLimit(0.2).add();
        fourBusNetwork.getLine("l23").newActivePowerLimits1().setPermanentLimit(0.25).add();
        fourBusNetwork.getLine("l34").newActivePowerLimits1().setPermanentLimit(0.15).add();
        fourBusNetwork.getLine("l13").newActivePowerLimits1().setPermanentLimit(0.1).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(fourBusNetwork, contingencies, monitors, securityAnalysisParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(3, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(3, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(2).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(3).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(4).getLimitViolationsResult().getLimitViolations().size());

        //Branch result for first contingency
        assertEquals(4, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().size());

        //Check branch results for flowTransfer computation for contingency on l14
        PostContingencyResult postContl14 = getPostContingencyResult(result, "l14");
        assertEquals("l14", postContl14.getContingency().getId());

        BranchResult brl14l12 = postContl14.getNetworkResult().getBranchResult("l12");
        assertEquals(0.333, brl14l12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l12.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l23 = postContl14.getNetworkResult().getBranchResult("l23");
        assertEquals(1.333, brl14l23.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l23.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l34 = postContl14.getNetworkResult().getBranchResult("l34");
        assertEquals(-1.0, brl14l34.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.0, brl14l34.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l13 = postContl14.getNetworkResult().getBranchResult("l13");
        assertEquals(1.666, brl14l13.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.666, brl14l13.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaModeAcAllBranchMonitoredFlowTransfer() {
        Network network = FourBusNetworkFactory.create();

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        assertEquals(5, result.getPostContingencyResults().size());

        for (PostContingencyResult r : result.getPostContingencyResults()) {
            assertEquals(4, r.getNetworkResult().getBranchResults().size());
        }

        //Check branch results for flowTransfer computation for contingency on l14
        PostContingencyResult postContl14 = getPostContingencyResult(result, "l14");
        assertEquals("l14", postContl14.getContingency().getId());

        BranchResult brl14l12 = postContl14.getNetworkResult().getBranchResult("l12");
        assertEquals(0.335, brl14l12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.336, brl14l12.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l23 = postContl14.getNetworkResult().getBranchResult("l23");
        assertEquals(1.335, brl14l23.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.336, brl14l23.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l34 = postContl14.getNetworkResult().getBranchResult("l34");
        assertEquals(-1.0, brl14l34.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.0, brl14l34.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l13 = postContl14.getNetworkResult().getBranchResult("l13");
        assertEquals(1.664, brl14l13.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.663, brl14l13.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithRemoteSharedControl() {
        Network network = VoltageControlNetworkFactory.createWithIdenticalTransformers();

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-66.667, preContingencyResult.getNetworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-66.667, preContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-66.667, preContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "tr1");
        assertEquals(-99.999, postContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-99.999, postContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithTransformerRemoteSharedControl() {
        Network network = VoltageControlNetworkFactory.createWithTransformerSharedRemoteControl();

        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.getLoadFlowParameters()
                .setTransformerVoltageControlOn(true);
        saParameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters()
                .setCreateResultExtension(true));

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, saParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-0.659, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.659, preContingencyResult.getNetworkResult().getBranchResult("T2wT").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.05, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getR1(), 0d);
        assertEquals(134.280, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getV1(), DELTA_V);
        assertEquals(33.989, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getV2(), DELTA_V);
        assertEquals(0.0, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getAngle1(), DELTA_ANGLE);
        assertEquals(-1.195796, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getAngle2(), DELTA_ANGLE);
        assertEquals(1.050302, preContingencyResult.getNetworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getContinuousR1(), LoadFlowAssert.DELTA_RHO);
        assertEquals(1.05, preContingencyResult.getNetworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getR1(), 0d);
        assertEquals(1.050302, preContingencyResult.getNetworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getContinuousR1(), LoadFlowAssert.DELTA_RHO);

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "T2wT2");
        assertEquals(-0.577, postContingencyResult.getNetworkResult().getBranchResult("T2wT").getQ1(), LoadFlowAssert.DELTA_POWER); // this assertion is not so relevant. It is more relevant to look at the logs.
        assertEquals(1.1, postContingencyResult.getNetworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getR1(), 0d);
        assertEquals(1.088228, postContingencyResult.getNetworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getContinuousR1(), LoadFlowAssert.DELTA_RHO);
    }

    @Test
    void testSaWithTransformerRemoteSharedControl2() {
        Network network = VoltageControlNetworkFactory.createWithTransformerSharedRemoteControl();

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setTransformerVoltageControlOn(true);

        List<Contingency> contingencies = List.of(new Contingency("N-2", List.of(new BranchContingency("T2wT"), new BranchContingency("T2wT2"))));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, lfParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-6.181, preContingencyResult.getNetworkResult().getBranchResult("LINE_12").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "N-2");
        assertEquals(-7.499, postContingencyResult.getNetworkResult().getBranchResult("LINE_12").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithShuntRemoteSharedControl() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, lfParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-100.337, preContingencyResult.getNetworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.168, preContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.168, preContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult tr2ContingencyResult = getPostContingencyResult(result, "tr2");
        assertEquals(-99.436, tr2ContingencyResult.getNetworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(99.436, tr2ContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithPhaseControl() {
        Network network = PhaseControlFactory.createNetworkWithT2wt();

        network.newLine().setId("L3")
                .setConnectableBus1("B1")
                .setBus1("B1")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        network.newLine().setId("L4")
                .setConnectableBus1("B3")
                .setBus1("B3")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        TwoWindingsTransformer ps1 = network.getTwoWindingsTransformer("PS1");
        ps1.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(ps1.getTerminal1())
                .setRegulationValue(83);

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setPhaseShifterRegulationOn(true);

        List<Contingency> contingencies = List.of(new Contingency("PS1", List.of(new BranchContingency("PS1")))); // allBranches(network);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, lfParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(5.682, preContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(59.019, preContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(5.682, preContingencyResult.getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(29.509, preContingencyResult.getNetworkResult().getBranchResult("L4").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(88.634, preContingencyResult.getNetworkResult().getBranchResult("PS1").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult ps1ContingencyResult = getPostContingencyResult(result, "PS1");
        assertEquals(50, ps1ContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0, ps1ContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER); // because no load on B3
        assertEquals(50, ps1ContingencyResult.getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0, ps1ContingencyResult.getNetworkResult().getBranchResult("L4").getP1(), LoadFlowAssert.DELTA_POWER); // because no load on B3
    }

    @Test
    void testSaWithShuntContingency() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getShuntCompensatorStream().forEach(shuntCompensator -> {
            shuntCompensator.setSectionCount(10);
        });

        List<Contingency> contingencies = List.of(new Contingency("SHUNT2", new ShuntCompensatorContingency("SHUNT2")),
                                                  new Contingency("tr3", new BranchContingency("tr3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(42.131, preContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.131, preContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult = getPostContingencyResult(result, "SHUNT2");
        assertEquals(0.0, contingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.792, contingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult tr3ContingencyResult = getPostContingencyResult(result, "tr3");
        assertEquals(42.792, tr3ContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithShuntContingency2() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getShuntCompensatorStream().forEach(shuntCompensator -> {
            shuntCompensator.setSectionCount(10);
        });

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);

        List<Contingency> contingencies = List.of(new Contingency("SHUNT2", new ShuntCompensatorContingency("SHUNT2")),
                                                  new Contingency("tr3", new BranchContingency("tr3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        CompletionException exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, lfParameters));
        assertEquals("Shunt compensator 'SHUNT2' with voltage control on: not supported yet", exception.getCause().getMessage());
    }

    @Test
    void testSaWithShuntContingency3() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getBusBreakerView().getBus("b2").getVoltageLevel().newShuntCompensator()
                .setId("SHUNT4")
                .setBus("b2")
                .setConnectableBus("b2")
                .setSectionCount(0)
                .newLinearModel()
                .setMaximumSectionCount(50)
                .setBPerSection(-1E-2)
                .setGPerSection(0.0)
                .add()
                .add();
        network.getShuntCompensatorStream().forEach(shuntCompensator -> {
            shuntCompensator.setSectionCount(10);
        });
        network.getGenerator("g1").setMaxP(1000);

        List<Contingency> contingencies = List.of(new Contingency("SHUNT2", new ShuntCompensatorContingency("SHUNT2")),
                new Contingency("SHUNTS", List.of(new ShuntCompensatorContingency("SHUNT2"), new ShuntCompensatorContingency("SHUNT4"))));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(82.342, preContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(41.495, preContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult = getPostContingencyResult(result, "SHUNT2");
        assertEquals(42.131, contingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.131, contingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult2 = getPostContingencyResult(result, "SHUNTS");
        assertEquals(-0.0027, contingencyResult2.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.792, contingencyResult2.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithShuntContingency4() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getShuntCompensatorStream().forEach(shuntCompensator -> {
            shuntCompensator.setSectionCount(10);
        });

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);

        List<Contingency> contingencies = List.of(new Contingency("SHUNT4", new ShuntCompensatorContingency("SHUNT4")),
                new Contingency("tr3", new BranchContingency("tr3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        CompletionException exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, lfParameters));
        assertEquals("Shunt compensator 'SHUNT4' not found in the network", exception.getCause().getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testDcSaWithLoadContingency(boolean dcFastMode) {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDc(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = List.of(new Contingency("l2", new LoadContingency("l2")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("l4", new LoadContingency("l4")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(129.411, preContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(64.706, preContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-58.823, preContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l2ContingencyResult = getPostContingencyResult(result, "l2");
        assertEquals(200.0, l2ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(57.143, l2ContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-71.428, l2ContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l4ContingencyResult = getPostContingencyResult(result, "l4");
        assertEquals(80.0, l4ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(40.0, l4ContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-100.0, l4ContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testDcSaWithGeneratorContingency(boolean dcFastMode) {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getGenerator("g2").setTargetV(400).setVoltageRegulatorOn(true);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        parameters.setDc(true);

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("g2", new GeneratorContingency("g2")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(110.0, preContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(40.0, preContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-50.0, preContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g1ContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(180.00, g1ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-30.0, g1ContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-50.0, g1ContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g2ContingencyResult = getPostContingencyResult(result, "g2");
        assertEquals(-60.000, g2ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(210.0, g2ContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-50.0, g2ContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithLoadContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l2", new LoadContingency("l2")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("l4", new LoadContingency("l4")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(129.412, preContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-64.706, preContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(58.823, preContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l2ContingencyResult = getPostContingencyResult(result, "l2");
        assertEquals(200.000, l2ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-57.142, l2ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(71.429, l2ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l4ContingencyResult = getPostContingencyResult(result, "l4");
        assertEquals(80.003, l4ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-40.002, l4ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(99.997, l4ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithDisconnectedLoadContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getLoad("l2").getTerminal().disconnect();

        List<Contingency> contingencies = List.of(new Contingency("l2", new LoadContingency("l2")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        // load is disconnected, contingency is skipped
        assertFalse(getOptionalPostContingencyResult(result, "l2").isPresent());
    }

    @Test
    void testSaWithLoadDetailContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getLoad("l2").newExtension(LoadDetailAdder.class).withVariableActivePower(40).withFixedActivePower(20).withVariableReactivePower(40).withFixedActivePower(0).add();
        network.getLoad("l4").newExtension(LoadDetailAdder.class).withVariableActivePower(100).withFixedActivePower(40).withVariableReactivePower(100).withFixedActivePower(0).add();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l2", new LoadContingency("l2")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("l4", new LoadContingency("l4")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(122.857, preContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-69.999, preContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, preContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l2ContingencyResult = getPostContingencyResult(result, "l2");
        assertEquals(200.000, l2ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-70.0, l2ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(49.999, l2ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult l4ContingencyResult = getPostContingencyResult(result, "l4");
        assertEquals(-59.982, l4ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-69.999, l4ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(49.999, l4ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaWithGeneratorContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getGenerator("g2").setTargetV(400).setVoltageRegulatorOn(true);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        parameters.setUseReactiveLimits(false);

        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("g2", new GeneratorContingency("g2")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(110, preContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-40, preContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, preContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g1ContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(180, g1ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(30, g1ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, g1ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(399.855, g1ContingencyResult.getNetworkResult().getBusResult("b1").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(400, g1ContingencyResult.getNetworkResult().getBusResult("b2").getV(), LoadFlowAssert.DELTA_V);

        // post-contingency tests
        PostContingencyResult g2ContingencyResult = getPostContingencyResult(result, "g2");
        assertEquals(-60.000, g2ContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-210.000, g2ContingencyResult.getNetworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, g2ContingencyResult.getNetworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(400.0, g2ContingencyResult.getNetworkResult().getBusResult("b1").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(399.891, g2ContingencyResult.getNetworkResult().getBusResult("b2").getV(), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testSaWithTransformerContingency() {
        Network network = VoltageControlNetworkFactory.createNetworkWithT2wt();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = List.of(new Contingency("T2wT", new BranchContingency("T2wT")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(22.111, preContingencyResult.getNetworkResult().getBranchResult("LINE_12").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult = getPostContingencyResult(result, "T2wT");
        assertEquals(11.228, contingencyResult.getNetworkResult().getBranchResult("LINE_12").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testPostContingencyFiltering() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.createWithFixedCurrentLimits());
        network.getLine("NHV1_NHV2_2").newCurrentLimits1()
                .setPermanentLimit(300)
                .add();
        network.getVoltageLevel("VLHV1").setLowVoltageLimit(410);

        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
        parameters.getIncreasedViolationsParameters().setFlowProportionalThreshold(0.0);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);

        List<LimitViolation> preContingencyLimitViolationsOnLine = result.getPreContingencyResult().getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSideAsTwoSides().equals(TwoSides.ONE)).toList();
        assertEquals(LimitViolationType.CURRENT, preContingencyLimitViolationsOnLine.get(0).getLimitType());
        assertEquals(456.769, preContingencyLimitViolationsOnLine.get(0).getValue(), LoadFlowAssert.DELTA_I);

        List<LimitViolation> postContingencyLimitViolationsOnLine = result.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSideAsTwoSides().equals(TwoSides.ONE)).toList();
        assertEquals(LimitViolationType.CURRENT, postContingencyLimitViolationsOnLine.get(0).getLimitType());
        assertEquals(1008.928, postContingencyLimitViolationsOnLine.get(0).getValue(), LoadFlowAssert.DELTA_I);

        List<LimitViolation> preContingencyLimitViolationsOnVoltageLevel = result.getPreContingencyResult().getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(LimitViolationType.LOW_VOLTAGE, preContingencyLimitViolationsOnVoltageLevel.get(0).getLimitType());
        assertEquals(402.143, preContingencyLimitViolationsOnVoltageLevel.get(0).getValue(), LoadFlowAssert.DELTA_V);

        List<LimitViolation> postContingencyLimitViolationsOnVoltageLevel = result.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(LimitViolationType.LOW_VOLTAGE, postContingencyLimitViolationsOnVoltageLevel.get(0).getLimitType());
        assertEquals(398.265, postContingencyLimitViolationsOnVoltageLevel.get(0).getValue(), LoadFlowAssert.DELTA_V);

        parameters.getIncreasedViolationsParameters().setFlowProportionalThreshold(1.5);
        parameters.getIncreasedViolationsParameters().setLowVoltageProportionalThreshold(0.1);
        parameters.getIncreasedViolationsParameters().setLowVoltageAbsoluteThreshold(5);
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);

        List<LimitViolation> postContingencyLimitViolationsOnLine2 = result2.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSideAsTwoSides().equals(TwoSides.ONE)).toList();
        assertEquals(0, postContingencyLimitViolationsOnLine2.size());

        List<LimitViolation> postContingencyLimitViolationsOnVoltageLevel2 = result2.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(0, postContingencyLimitViolationsOnVoltageLevel2.size());
    }

    @Test
    void testViolationsWeakenedOrEquivalent() {
        LimitViolation violation1 = new LimitViolation("voltageLevel1", LimitViolationType.HIGH_VOLTAGE, 420, 1, 421);
        LimitViolation violation2 = new LimitViolation("voltageLevel1", LimitViolationType.HIGH_VOLTAGE, 420, 1, 425.20);
        SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters = new SecurityAnalysisParameters.IncreasedViolationsParameters();
        violationsParameters.setFlowProportionalThreshold(1.5);
        violationsParameters.setHighVoltageProportionalThreshold(0.1);
        violationsParameters.setHighVoltageAbsoluteThreshold(3);
        assertFalse(LimitViolationManager.violationWeakenedOrEquivalent(violation1, violation2, violationsParameters));
        violationsParameters.setHighVoltageProportionalThreshold(0.01); // 4.21 kV
        violationsParameters.setHighVoltageAbsoluteThreshold(5);
        assertTrue(LimitViolationManager.violationWeakenedOrEquivalent(violation1, violation2, violationsParameters));

        LimitViolation violation3 = new LimitViolation("voltageLevel1", LimitViolationType.LOW_VOLTAGE, 380, 1, 375);
        LimitViolation violation4 = new LimitViolation("voltageLevel1", LimitViolationType.LOW_VOLTAGE, 380, 1, 371.26);
        violationsParameters.setFlowProportionalThreshold(1.5);
        violationsParameters.setLowVoltageProportionalThreshold(0.1);
        violationsParameters.setLowVoltageAbsoluteThreshold(3);
        assertFalse(LimitViolationManager.violationWeakenedOrEquivalent(violation3, violation4, violationsParameters));
        violationsParameters.setLowVoltageProportionalThreshold(0.01); // 3.75 kV
        violationsParameters.setLowVoltageAbsoluteThreshold(5);
        assertTrue(LimitViolationManager.violationWeakenedOrEquivalent(violation3, violation4, violationsParameters));

        assertFalse(LimitViolationManager.violationWeakenedOrEquivalent(violation1, violation4, violationsParameters));
    }

    @Test
    void testViolationOnThreeWindingsTransformersLeg() {
        Network network = T3wtFactory.create();
        network.getThreeWindingsTransformer("3wt").getLeg2().newCurrentLimits()
                .setPermanentLimit(400.)
                .beginTemporaryLimit()
                .setName("60'")
                .setAcceptableDuration(60)
                .setValue(500.)
                .endTemporaryLimit()
                .add();

        SecurityAnalysisResult result = runSecurityAnalysis(network, List.of(), new LoadFlowParameters());
        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        LimitViolation expected = new LimitViolation("3wt", null, LimitViolationType.CURRENT, "permanent",
                60, 400., 1.0F, 435.0831773201809, TwoSides.TWO);
        int compare = LimitViolations.comparator().compare(expected,
                result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare);
    }

    @Test
    void testPhaseShifterNecessaryForConnectivity() {
        Network network = PhaseControlFactory.createNetworkWithT2wt();

        // switch PS1 to active power control
        var ps1 = network.getTwoWindingsTransformer("PS1");
        ps1.getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setRegulationValue(83);

        LoadFlowParameters parameters = new LoadFlowParameters()
                .setPhaseShifterRegulationOn(true);

        List<Contingency> contingencies = List.of(Contingency.line("L2"), Contingency.twoWindingsTransformer("PS1"), Contingency.line("L1")); // I added L2 and PS1 before to assert there is no impact on L1 contingency

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);
        assertEquals(3, result.getPostContingencyResults().size());
        PostContingencyResult l1ContingencyResult = getPostContingencyResult(result, "L1");
        assertSame(PostContingencyComputationStatus.CONVERGED, l1ContingencyResult.getStatus());
        assertEquals(100.3689, l1ContingencyResult.getNetworkResult().getBranchResult("PS1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-100.1844, l1ContingencyResult.getNetworkResult().getBranchResult("PS1").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithNonImpedantLineConnectedToSlackBus() {
        Network network = IeeeCdfNetworkFactory.create14();
        network.getLine("L1-2-1").setR(0).setX(0);
        network.getLine("L4-5-1").setR(0).setX(0);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = Collections.emptyList();

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);
        assertEquals(20, result.getPostContingencyResults().size()); // assert there is no contingency simulation failure
    }

    @Test
    void testWithNonImpedantLineConnectedToSlackBusFastDc() {
        Network network = IeeeCdfNetworkFactory.create14();
        network.getLine("L1-2-1").setR(0).setX(0);
        network.getLine("L4-5-1").setR(0).setX(0);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        List<StateMonitor> monitors = Collections.emptyList();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        OpenLoadFlowParameters.create(lfParameters);
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(20, result.getPostContingencyResults().size()); // assert there is no contingency simulation failure
    }

    @Test
    void testHvdcAcEmulation() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();
        network.getGeneratorStream().forEach(generator -> generator.setMaxP(10));

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                .setHvdcAcEmulation(true);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(Contingency.line("l12"));
        contingencies.add(Contingency.line("l46"));
        contingencies.add(Contingency.generator("g1"));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-1.768, preContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g1ContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(-2.783, g1ContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        List<Contingency> contingencies2 = new ArrayList<>();
        contingencies2.add(Contingency.hvdcLine("hvdc34"));
        contingencies2.add(Contingency.generator("g1"));
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies2, monitors, parameters);

        // post-contingency tests
        PostContingencyResult hvdcContingencyResult = getPostContingencyResult(result2, "hvdc34");
        assertEquals(-2.000, hvdcContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult g1ContingencyResult2 = getPostContingencyResult(result, "g1");
        assertEquals(-2.783, g1ContingencyResult2.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSetpoint() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();
        network.getGeneratorStream().forEach(generator -> generator.setMaxP(10));

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                .setHvdcAcEmulation(false); // will be operated in set point
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-0.021, preContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g1ContingencyResult = getPostContingencyResult(result, "hvdc34");
        assertEquals(-1.998, g1ContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnHvdcLcc() {
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setSlackBusPMaxMismatch(0.0001);
        parameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        network.getHvdcLine("hvdc34").getConverterStation1().getTerminal().disconnect();
        network.getHvdcLine("hvdc34").getConverterStation2().getTerminal().disconnect();
        runLoadFlow(network, parameters);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(1.360, preContingencyResult.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.360, preContingencyResult.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.596, preContingencyResult.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "hvdc34");
        assertEquals(network.getLine("l12").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l12").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l12").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l13").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l13").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l13").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l23").getQ1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnHvdcVsc() {
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setSlackBusPMaxMismatch(0.0001);
        parameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        network.getHvdcLine("hvdc34").getConverterStation1().getTerminal().disconnect();
        network.getHvdcLine("hvdc34").getConverterStation2().getTerminal().disconnect();
        runLoadFlow(network, parameters);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(1.25, preContingencyResult.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.228, preContingencyResult.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.727, preContingencyResult.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "hvdc34");
        assertEquals(network.getLine("l12").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l12").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l12").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l13").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l13").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l13").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l23").getQ1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testEmptyNetwork() {
        Network network = Network.create("empty", "");
        SecurityAnalysisResult result = runSecurityAnalysis(network);
        assertSame(LoadFlowResult.ComponentResult.Status.FAILED, result.getPreContingencyResult().getStatus());
    }

    @Test
    void testDivergenceStatus() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getLine("NHV1_NHV2_1").setR(100).setX(-999);
        network.getLine("NHV1_NHV2_2").setR(100).setX(-999);
        SecurityAnalysisResult result = runSecurityAnalysis(network);
        assertSame(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED, result.getPreContingencyResult().getStatus());
    }

    @Test
    void testSwitchContingency() {
        Network network = createNodeBreakerNetwork();

        List<Contingency> contingencies = List.of(new Contingency("C", new SwitchContingency("C")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(301.884, preContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-300, preContingencyResult.getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.884, preContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-300, preContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "C");
        assertEquals(0.099, postContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.083, postContingencyResult.getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(607.682, postContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-599.918, postContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSwitchContingency2() {
        Network network = BusBreakerNetworkFactory.create();

        List<Contingency> contingencies = List.of(new Contingency("C", new SwitchContingency("C")),
                                                  new Contingency("C2", new LoadContingency("LD")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "C");
        assertEquals(607.782, postContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-600.016, postContingencyResult.getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, postContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0163, postContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult postContingencyResult2 = getPostContingencyResult(result, "C2");
        assertEquals(0.0180, postContingencyResult2.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, postContingencyResult2.getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0180, postContingencyResult2.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, postContingencyResult2.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSwitchContingencyNotFound() {
        Network network = createNodeBreakerNetwork();

        List<Contingency> contingencies = List.of(new Contingency("X", new SwitchContingency("X")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        var e = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Switch 'X' not found in the network", e.getCause().getMessage());
    }

    @Test
    void testSwitchLoopIssue() {
        Network network = SwitchLoopIssueNetworkFactory.create();

        List<Contingency> contingencies = List.of(Contingency.line("L1"));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        var result = runSecurityAnalysis(network, contingencies, monitors);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(-299.977, preContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.862, preContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "L1");
        assertEquals(-599.882, postContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(608.214, postContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testDcPermanentCurrentLimitViolations(boolean dcFastMode) {
        Network network = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        network.getLine("l14").newCurrentLimits1().setPermanentLimit(60.0).add();
        network.getLine("l12").newCurrentLimits1().setPermanentLimit(120.0).add();
        network.getLine("l23").newCurrentLimits2().setPermanentLimit(150.0).add();
        network.getLine("l34").newCurrentLimits1().setPermanentLimit(90.0).add();
        network.getLine("l13").newCurrentLimits2().setPermanentLimit(60.0).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(2, getPostContingencyResult(result, "l14").getLimitViolationsResult().getLimitViolations().size());
        assertEquals(192.450, getPostContingencyResult(result, "l14").getNetworkResult().getBranchResult("l12").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(962.250, getPostContingencyResult(result, "l14").getNetworkResult().getBranchResult("l13").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(2, getPostContingencyResult(result, "l12").getLimitViolationsResult().getLimitViolations().size());
        assertEquals(192.450, getPostContingencyResult(result, "l12").getNetworkResult().getBranchResult("l14").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(962.250, getPostContingencyResult(result, "l12").getNetworkResult().getBranchResult("l13").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(4, getPostContingencyResult(result, "l13").getLimitViolationsResult().getLimitViolations().size());
        assertEquals(577.350, getPostContingencyResult(result, "l13").getNetworkResult().getBranchResult("l12").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(577.350, getPostContingencyResult(result, "l13").getNetworkResult().getBranchResult("l14").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(1154.700, getPostContingencyResult(result, "l13").getNetworkResult().getBranchResult("l23").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(1154.700, getPostContingencyResult(result, "l13").getNetworkResult().getBranchResult("l34").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(4, getPostContingencyResult(result, "l23").getLimitViolationsResult().getLimitViolations().size());
        assertEquals(577.350, getPostContingencyResult(result, "l23").getNetworkResult().getBranchResult("l12").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(384.900, getPostContingencyResult(result, "l23").getNetworkResult().getBranchResult("l14").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(1347.150, getPostContingencyResult(result, "l23").getNetworkResult().getBranchResult("l13").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(962.250, getPostContingencyResult(result, "l23").getNetworkResult().getBranchResult("l34").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(4, getPostContingencyResult(result, "l34").getLimitViolationsResult().getLimitViolations().size());
        assertEquals(384.900, getPostContingencyResult(result, "l34").getNetworkResult().getBranchResult("l12").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(577.350, getPostContingencyResult(result, "l34").getNetworkResult().getBranchResult("l14").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(1347.150, getPostContingencyResult(result, "l34").getNetworkResult().getBranchResult("l13").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(962.250, getPostContingencyResult(result, "l34").getNetworkResult().getBranchResult("l23").getI1(), LoadFlowAssert.DELTA_I);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testDcTemporaryCurrentLimitViolations(boolean dcFastMode) {
        Network network = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true)
                .setDcPowerFactor(Math.tan(0.4));
        setSlackBusId(lfParameters, "b1_vl");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        network.getLine("l14").newCurrentLimits1().setPermanentLimit(60.0)
                .beginTemporaryLimit().setName("60").setAcceptableDuration(Integer.MAX_VALUE).setValue(200.0).endTemporaryLimit()
                .beginTemporaryLimit().setName("0").setAcceptableDuration(60).setValue(Double.MAX_VALUE).endTemporaryLimit().add();
        network.getLine("l12").newCurrentLimits1().setPermanentLimit(120.0)
                .beginTemporaryLimit().setName("60").setAcceptableDuration(Integer.MAX_VALUE).setValue(300.0).endTemporaryLimit()
                .beginTemporaryLimit().setName("0").setAcceptableDuration(60).setValue(Double.MAX_VALUE).endTemporaryLimit().add();
        network.getLine("l23").newCurrentLimits2().setPermanentLimit(150.0)
                .beginTemporaryLimit().setName("60").setAcceptableDuration(Integer.MAX_VALUE).setValue(500.0).endTemporaryLimit()
                .beginTemporaryLimit().setName("0").setAcceptableDuration(60).setValue(Double.MAX_VALUE).endTemporaryLimit().add();
        network.getLine("l34").newCurrentLimits1().setPermanentLimit(90.0)
                .beginTemporaryLimit().setName("60").setAcceptableDuration(Integer.MAX_VALUE).setValue(300.0).endTemporaryLimit()
                .beginTemporaryLimit().setName("0").setAcceptableDuration(60).setValue(Double.MAX_VALUE).endTemporaryLimit().add();
        network.getLine("l13").newCurrentLimits2().setPermanentLimit(60.0)
                .beginTemporaryLimit().setName("60").setAcceptableDuration(Integer.MAX_VALUE).setValue(300.0).endTemporaryLimit()
                .beginTemporaryLimit().setName("0").setAcceptableDuration(60).setValue(Double.MAX_VALUE).endTemporaryLimit().add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(2).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(3).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(4).getLimitViolationsResult().getLimitViolations().size());
    }

    @Test
    void testThreeWindingsTransformerContingency() {
        Network network = VoltageControlNetworkFactory.createNetworkWithT3wt();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        setSlackBusId(parameters, "VL_1");
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        List<Contingency> contingencies = List.of(new Contingency("T3wT", new ThreeWindingsTransformerContingency("T3wT")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        network.getThreeWindingsTransformer("T3wT").getLeg1().getTerminal().disconnect();
        network.getThreeWindingsTransformer("T3wT").getLeg2().getTerminal().disconnect();
        network.getThreeWindingsTransformer("T3wT").getLeg3().getTerminal().disconnect();
        setSlackBusId(parameters, "VL_1");
        loadFlowRunner.run(network, parameters);

        PostContingencyResult contingencyResult = getPostContingencyResult(result, "T3wT");
        assertEquals(network.getLine("LINE_12").getTerminal2().getP(), contingencyResult.getNetworkResult().getBranchResult("LINE_12").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("LINE_12").getTerminal2().getQ(), contingencyResult.getNetworkResult().getBranchResult("LINE_12").getQ2(), LoadFlowAssert.DELTA_POWER);

        network.getThreeWindingsTransformer("T3wT").getLeg1().getTerminal().connect();
        network.getThreeWindingsTransformer("T3wT").getLeg2().getTerminal().connect();
        network.getThreeWindingsTransformer("T3wT").getLeg3().getTerminal().connect();
        List<Contingency> contingencies2 = List.of(new Contingency("T3wT2", new ThreeWindingsTransformerContingency("T3wT2")));
        var e = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies2, monitors, securityAnalysisParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Three windings transformer 'T3wT2' not found in the network", e.getCause().getMessage());
    }

    @Test
    void testDanglingLineContingency() {
        Network network = BoundaryFactory.createWithLoad();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        List<Contingency> contingencies = List.of(new Contingency("dl1", new DanglingLineContingency("dl1")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(75.18, result.getPreContingencyResult().getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(3.333, getPostContingencyResult(result, "dl1").getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void reportTest() throws IOException {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        ReportNode reportNode = ReportNode.newRootReportNode()
                .withMessageTemplate("TestSecurityAnalysis", "Test security analysis report")
                .build();

        LoadFlowParameters loadFlowParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(loadFlowParameters)
                .setMaxRealisticVoltage(1.5);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters()
                .setLoadFlowParameters(loadFlowParameters);
        runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters, reportNode);

        assertReportEquals("/saReport.txt", reportNode);
    }

    @Test
    void testBranchOpenAtOneSideLoss() {
        var network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();
        network.getLine("l46").getTerminal1().disconnect();
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), new SecurityAnalysisParameters(), ReportNode.NO_OP);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
    }

    @Test
    void testStatusConversion() {
        assertEquals(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED,
                buildTestAcLoadFlowResult(AcSolverStatus.CONVERGED, OuterLoopStatus.UNSTABLE).toComponentResultStatus().status());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED,
                buildTestAcLoadFlowResult(AcSolverStatus.CONVERGED, OuterLoopStatus.STABLE).toComponentResultStatus().status());
        assertEquals(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED,
                buildTestAcLoadFlowResult(AcSolverStatus.MAX_ITERATION_REACHED, OuterLoopStatus.STABLE).toComponentResultStatus().status());
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED,
                buildTestAcLoadFlowResult(AcSolverStatus.SOLVER_FAILED, OuterLoopStatus.STABLE).toComponentResultStatus().status());
        assertEquals(LoadFlowResult.ComponentResult.Status.NO_CALCULATION,
                buildTestAcLoadFlowResult(AcSolverStatus.NO_CALCULATION, OuterLoopStatus.STABLE).toComponentResultStatus().status());
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED,
                buildTestAcLoadFlowResult(AcSolverStatus.UNREALISTIC_STATE, OuterLoopStatus.STABLE).toComponentResultStatus().status());

        assertEquals(PostContingencyComputationStatus.MAX_ITERATION_REACHED,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.CONVERGED, OuterLoopStatus.UNSTABLE)));
        assertEquals(PostContingencyComputationStatus.CONVERGED,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.CONVERGED, OuterLoopStatus.STABLE)));
        assertEquals(PostContingencyComputationStatus.MAX_ITERATION_REACHED,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.MAX_ITERATION_REACHED, OuterLoopStatus.STABLE)));
        assertEquals(PostContingencyComputationStatus.SOLVER_FAILED,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.SOLVER_FAILED, OuterLoopStatus.STABLE)));
        assertEquals(PostContingencyComputationStatus.NO_IMPACT,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.NO_CALCULATION, OuterLoopStatus.STABLE)));
        assertEquals(PostContingencyComputationStatus.FAILED,
                AcSecurityAnalysis.postContingencyStatusFromAcLoadFlowResult(buildTestAcLoadFlowResult(AcSolverStatus.UNREALISTIC_STATE, OuterLoopStatus.STABLE)));
    }

    private AcLoadFlowResult buildTestAcLoadFlowResult(AcSolverStatus solverStatus, OuterLoopStatus outerLoopStatus) {
        LfNetwork lfNetwork = Mockito.mock(LfNetwork.class);
        return new AcLoadFlowResult(lfNetwork, 0, 0, solverStatus, new OuterLoopResult("", outerLoopStatus), 0d, 0d);
    }

    @Test
    void testConnectivityResultWhenNoSplitAc() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l12")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test AC
        parameters.getLoadFlowParameters().setDc(false);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
        var postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(0, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testConnectivityResultWhenNoSplitDc(boolean dcFastMode) {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l12")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test DC
        parameters.getLoadFlowParameters().setDc(true);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
        var postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(0, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
    }

    @Test
    void testConnectivityResultOnSplitAc() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test AC
        parameters.getLoadFlowParameters().setDc(false);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, new SecurityAnalysisParameters(), ReportNode.NO_OP);
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(1, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
        assertEquals(3.0, postContingencyResult.getConnectivityResult().getDisconnectedLoadActivePower());
        assertEquals(2.0, postContingencyResult.getConnectivityResult().getDisconnectedGenerationActivePower());
        assertTrue(postContingencyResult.getConnectivityResult().getDisconnectedElements().containsAll(
                List.of("d4", "d5", "g6", "l46", "l34", "l45", "l56")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testConnectivityResultOnSplitDc(boolean dcFastMode) {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test DC
        parameters.getLoadFlowParameters().setDc(true);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(1, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
        assertEquals(3.0, postContingencyResult.getConnectivityResult().getDisconnectedLoadActivePower());
        assertEquals(2.0, postContingencyResult.getConnectivityResult().getDisconnectedGenerationActivePower());
        assertTrue(postContingencyResult.getConnectivityResult().getDisconnectedElements().containsAll(List.of("d4", "d5", "g6", "l46", "l34", "l45", "l56")));
    }

    @Test
    void testConnectivityResultOnSplitThreeCCAc() {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34"), new BranchContingency("l45")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test AC
        parameters.getLoadFlowParameters().setDc(false);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(2, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testConnectivityResultOnSplitThreeCCDc(boolean dcFastMode) {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34"), new BranchContingency("l45")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        //Test DC
        parameters.getLoadFlowParameters().setDc(true);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(2, postContingencyResult.getConnectivityResult().getCreatedSynchronousComponentCount());
    }

    @Test
    void testDcFastModeInFirstSlackComponentOnly() {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l34"), new BranchContingency("l45")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        // Run fast DC security analysis
        parameters.getLoadFlowParameters().setDc(true);
        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(parameters.getLoadFlowParameters())
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(true);
        parameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<List<String>> slackBusesIdList = List.of(List.of("b3_vl"), List.of("b3_vl", "b7_vl"), List.of("b3_vl", "b7_vl", "b10_vl"));
        // verify that for each list of slacks buses, fast dc sa runs only in component of first slack bus
        slackBusesIdList.forEach(slackBusesId -> {
            openLoadFlowParameters.setSlackBusesIds(slackBusesId)
                    .setMaxSlackBusCount(slackBusesId.size());
            SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);
            PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
            assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());

            // verify only flows in slack component have been computed
            assertEquals(3, postContingencyResult.getNetworkResult().getBranchResults().size());

            // verify flows on the branches
            assertEquals(-1, postContingencyResult.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
            assertEquals(0, postContingencyResult.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
            assertEquals(1, postContingencyResult.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

            // verify active load and generation of other components have been lost
            assertEquals(6, postContingencyResult.getConnectivityResult().getDisconnectedGenerationActivePower(), LoadFlowAssert.DELTA_POWER);
            assertEquals(7, postContingencyResult.getConnectivityResult().getDisconnectedLoadActivePower(), LoadFlowAssert.DELTA_POWER);
        });
    }

    @Test
    void testStaticVarCompensatorContingency() {
        Network network = VoltageControlNetworkFactory.createWithStaticVarCompensator();
        network.getStaticVarCompensator("svc1").setVoltageSetpoint(385).setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE);
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("svc1", new StaticVarCompensatorContingency("svc1")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        // test AC
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);

        // compare with a simple load low
        network.getStaticVarCompensator("svc1").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters.getLoadFlowParameters());

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "svc1");
        assertEquals(network.getLine("l1").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getP(), postContingencyResult.getNetworkResult().getBranchResult("l1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l1").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l1").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testStaticVarCompensatorContingencyWithStandByAutomaton() {
        Network network = VoltageControlNetworkFactory.createWithStaticVarCompensator();
        StaticVarCompensator svc1 = network.getStaticVarCompensator("svc1");
        svc1.setVoltageSetpoint(385).setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE);
        svc1.newExtension(StandbyAutomatonAdder.class)
                .withHighVoltageThreshold(400)
                .withLowVoltageThreshold(380)
                .withLowVoltageSetpoint(385)
                .withHighVoltageSetpoint(395)
                .withB0(-0.001f)
                .withStandbyStatus(true)
                .add();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Contingency> contingencies = List.of(new Contingency("svc1", new StaticVarCompensatorContingency("svc1")),
                new Contingency("ld1", new LoadContingency("ld1")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();

        // test AC
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters, ReportNode.NO_OP);

        // compare with a simple load low
        network.getStaticVarCompensator("svc1").getTerminal().disconnect();
        loadFlowRunner.run(network);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "svc1");
        assertEquals(network.getLine("l1").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getP(), postContingencyResult.getNetworkResult().getBranchResult("l1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal1().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l1").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getQ(), postContingencyResult.getNetworkResult().getBranchResult("l1").getQ2(), LoadFlowAssert.DELTA_POWER);

        // test restore.
        network.getStaticVarCompensator("svc1").getTerminal().connect();
        network.getLoad("ld1").getTerminal().disconnect();
        loadFlowRunner.run(network);
        PostContingencyResult postContingencyResult2 = getPostContingencyResult(result, "ld1");
        assertEquals(network.getLine("l1").getTerminal1().getP(), postContingencyResult2.getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getP(), postContingencyResult2.getNetworkResult().getBranchResult("l1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal1().getQ(), postContingencyResult2.getNetworkResult().getBranchResult("l1").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l1").getTerminal2().getQ(), postContingencyResult2.getNetworkResult().getBranchResult("l1").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testBusBarSectionContingency() {
        Network network = createNodeBreakerNetwork();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        List<Contingency> contingencies = Stream.of("BBS1")
                .map(id -> new Contingency(id, new BusbarSectionContingency(id)))
                .collect(Collectors.toList());

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        NetworkResult preContingencyNetworkResult = result.getPreContingencyResult().getNetworkResult();
        assertEquals(446.765, preContingencyNetworkResult.getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(446.765, preContingencyNetworkResult.getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);

        assertEquals(945.514, getPostContingencyResult(result, "BBS1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertNull(getPostContingencyResult(result, "BBS1").getNetworkResult().getBranchResult("L1"));

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setContingencyPropagation(false);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        NetworkResult preContingencyNetworkResult2 = result2.getPreContingencyResult().getNetworkResult();
        assertEquals(446.765, preContingencyNetworkResult2.getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(446.765, preContingencyNetworkResult2.getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);

        assertEquals(915.953, getPostContingencyResult(result2, "BBS1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(84.923, getPostContingencyResult(result2, "BBS1").getNetworkResult().getBranchResult("L1").getI2(), LoadFlowAssert.DELTA_I);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testDcBusBarSectionContingency(boolean dcFastMode) {
        Network network = createNodeBreakerNetwork();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        lfParameters.setDc(true);
        setSlackBusId(lfParameters, "VL1_1");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        // set dc sa mode
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = Stream.of("BBS1")
                .map(id -> new Contingency(id, new BusbarSectionContingency(id)))
                .collect(Collectors.toList());

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        NetworkResult preContingencyNetworkResult = result.getPreContingencyResult().getNetworkResult();
        assertEquals(433.012, preContingencyNetworkResult.getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(433.012, preContingencyNetworkResult.getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);

        assertEquals(866.025, getPostContingencyResult(result, "BBS1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
    }

    @Test
    void testBusBarSectionContingencyIssue() {
        Network network = NodeBreakerNetworkFactory.create3Bars();
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_0"); // issue with slack bus to be disabled.
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new SwitchContingency("B1"), new SwitchContingency("C1"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "contingency");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(400.0, postContingencyResult.getNetworkResult().getBusResult("BBS2").getV(), DELTA_V);
        assertEquals(0.0, postContingencyResult.getNetworkResult().getBusResult("BBS2").getAngle(), DELTA_ANGLE);
        assertEquals(400.0, postContingencyResult.getNetworkResult().getBusResult("BBS3").getV(), DELTA_V);
        assertEquals(0.0, postContingencyResult.getNetworkResult().getBusResult("BBS3").getAngle(), DELTA_ANGLE);
        assertEquals(393.577, postContingencyResult.getNetworkResult().getBusResult("BBS4").getV(), DELTA_V);
        assertEquals(-3.561631, postContingencyResult.getNetworkResult().getBusResult("BBS4").getAngle(), DELTA_ANGLE);
    }

    @Test
    void testLoadContingencyNoImpact() {
        Network network = SecurityAnalysisTestNetworkFactory.createWithFixedCurrentLimits();
        List<Contingency> contingencies = List.of(new Contingency("Load contingency", new LoadContingency("LD1")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);
        assertEquals(1, result.getPostContingencyResults().size());
        contingencies = List.of(new Contingency("Load contingency", new LoadContingency("LD1")),
                                new Contingency("Switch contingency", new SwitchContingency("S1VL1_BBS1_GEN_DISCONNECTOR")));
        result = runSecurityAnalysis(network, contingencies);
        assertEquals(2, result.getPostContingencyResults().size());
    }

    @Test
    void testWithVoltageRemoteControl() {
        Network network = VoltageControlNetworkFactory.createWithSimpleRemoteControl();
        List<Contingency> contingencies = List.of(new Contingency("contingency",
                List.of(new BranchContingency("l12"), new BranchContingency("l31"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "b4_vl");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        assertDoesNotThrow(() -> {
            runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        });
    }

    @Test
    void testWithReactivePowerRemoteControl() {
        Network network = ReactivePowerControlNetworkFactory.createWithGeneratorRemoteControl();
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l34"), Collections.emptySet(), Collections.emptySet()));
        List<Contingency> contingencies = List.of(
                new Contingency("contingency1", List.of(new BranchContingency("l34"))),
                new Contingency("contingency2", List.of(new GeneratorContingency("g4")))
        );
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(lfParameters);
        openLoadFlowParameters.setGeneratorReactivePowerRemoteControl(true)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxReactivePowerMismatch(DELTA_POWER); // needed to ensure convergence within a DELTA_POWER
                                                           // tolerance in Q for the controlled branch
        LoadFlow.run(network, lfParameters);
        assertReactivePowerEquals(4, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(6.198, network.getLine("l14").getTerminal2());

        network.getGenerator("g4").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(0.124, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-0.124, network.getLine("l14").getTerminal2());
        network.getGenerator("g4").getTerminal().connect();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        // pre contingency
        // - l34 is controlled by g4 to have Q2 = 4 MVar
        assertEquals(4, result.getPreContingencyResult().getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(6.198, result.getPreContingencyResult().getNetworkResult().getBranchResult("l14").getQ2(), DELTA_POWER);
        // post contingency 1
        assertEquals(2, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult1 = getPostContingencyResult(result, "contingency1");
        assertEquals(0, postContingencyResult1.getNetworkResult().getBranchResult("l14").getQ2(), DELTA_POWER);
        // post contingency 2: generator g4 is off
        // - l34 is no longer controlled by g4, so it must have its Q2 != 4 (pre contingency)
        PostContingencyResult postContingencyResult2 = getPostContingencyResult(result, "contingency2");
        assertEquals(0.124, postContingencyResult2.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-0.124, postContingencyResult2.getNetworkResult().getBranchResult("l14").getQ2(), DELTA_POWER);
    }

    @Test
    void testWithReactivePowerRemoteControl2() {
        Network network = ReactivePowerControlNetworkFactory.createWithGeneratorRemoteControl2();
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l12", "l34"), Collections.emptySet(), Collections.emptySet()));
        List<Contingency> contingencies = List.of(
                new Contingency("contingency1", List.of(new BranchContingency("l12"))),
                new Contingency("contingency2", List.of(new GeneratorContingency("g4")))
        );
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        lfParameters.setUseReactiveLimits(true);
        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(lfParameters);
        openLoadFlowParameters.setGeneratorReactivePowerRemoteControl(true)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxReactivePowerMismatch(DELTA_POWER); // needed to ensure convergence within a DELTA_POWER
                                                           // tolerance in Q for the controlled branch
        LoadFlow.run(network, lfParameters);
        assertReactivePowerEquals(1, network.getLine("l12").getTerminal2());
        assertReactivePowerEquals(4.409, network.getLine("l34").getTerminal2());

        network.getBranch("l12").getTerminal1().disconnect();
        network.getBranch("l12").getTerminal2().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(0.481, network.getLine("l34").getTerminal2());
        network.getBranch("l12").getTerminal1().connect();
        network.getBranch("l12").getTerminal2().connect();

        network.getGenerator("g4").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(-0.066, network.getLine("l12").getTerminal2());
        assertReactivePowerEquals(0.125, network.getLine("l34").getTerminal2());
        network.getGenerator("g4").getTerminal().connect();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre contingency
        // - l12 is controlled by g4 to have Q2 = 1 MVar
        assertEquals(1, result.getPreContingencyResult().getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        assertEquals(4.409, result.getPreContingencyResult().getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(2, result.getPostContingencyResults().size());
        // post contingency 1: controlled branch l12 is off
        assertEquals(0.481, getPostContingencyResult(result, "contingency1").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        // post contingency 2: generator g4 is off
        assertEquals(-0.066, getPostContingencyResult(result, "contingency2").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        assertEquals(0.125, getPostContingencyResult(result, "contingency2").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
    }

    @Test
    void testWithSharedReactivePowerRemoteControl() {
        Network network = ReactivePowerControlNetworkFactory.createWithGeneratorsRemoteControlShared();
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l12", "l34"), Collections.emptySet(), Collections.emptySet()));
        List<Contingency> contingencies = List.of(
                new Contingency("contingency1", List.of(new BranchContingency("l34"))),
                new Contingency("contingency2", List.of(new GeneratorContingency("g1"))),
                new Contingency("contingency3", List.of(new GeneratorContingency("g4"))),
                new Contingency("contingency4", List.of(new GeneratorContingency("g1"), new GeneratorContingency("g1Bis"))),
                new Contingency("contingency5", List.of(new GeneratorContingency("g1"), new GeneratorContingency("g4")))
        );
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        lfParameters.setUseReactiveLimits(true);
        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.create(lfParameters);
        openLoadFlowParameters.setGeneratorReactivePowerRemoteControl(true)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxReactivePowerMismatch(DELTA_POWER); // needed to ensure convergence within a DELTA_POWER
                                                           // tolerance in Q for the controlled branch
        LoadFlow.run(network, lfParameters);
        assertReactivePowerEquals(2, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-2.814, network.getLine("l12").getTerminal2());

        network.getBranch("l34").getTerminal1().disconnect();
        network.getBranch("l34").getTerminal2().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(0.927, network.getLine("l12").getTerminal2());
        network.getBranch("l34").getTerminal1().connect();
        network.getBranch("l34").getTerminal2().connect();

        network.getGenerator("g1").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(2, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-2.126, network.getLine("l12").getTerminal2());
        network.getGenerator("g1").getTerminal().connect();

        network.getGenerator("g4").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(2, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-5.960, network.getLine("l12").getTerminal2());
        network.getGenerator("g4").getTerminal().connect();

        network.getGenerator("g1").getTerminal().disconnect();
        network.getGenerator("g1Bis").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(2, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-0.927, network.getLine("l12").getTerminal2());
        network.getGenerator("g1").getTerminal().connect();
        network.getGenerator("g1Bis").getTerminal().connect();

        network.getGenerator("g1").getTerminal().disconnect();
        network.getGenerator("g4").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertReactivePowerEquals(2, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-5.962, network.getLine("l12").getTerminal2());
        network.getGenerator("g1").getTerminal().connect();
        network.getGenerator("g4").getTerminal().connect();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre contingency
        // - l34 is controlled by g1, g1Bis and g4 to have Q2 = 2 MVar
        assertEquals(2, result.getPreContingencyResult().getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-2.814, result.getPreContingencyResult().getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        assertEquals(5, result.getPostContingencyResults().size());
        // post contingency 1: branch l34 is off
        assertEquals(0.927, getPostContingencyResult(result, "contingency1").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        // post contingency 2: g1 is off
        assertEquals(2, getPostContingencyResult(result, "contingency2").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-2.126, getPostContingencyResult(result, "contingency2").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        // post contingency 3: g4 is off
        assertEquals(2, getPostContingencyResult(result, "contingency3").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-5.960, getPostContingencyResult(result, "contingency3").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        // post contingency 4: g1 and g1Bis are off
        assertEquals(2, getPostContingencyResult(result, "contingency4").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-0.927, getPostContingencyResult(result, "contingency4").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        // post contingency 4: g1 and g4 are off
        assertEquals(2, getPostContingencyResult(result, "contingency5").getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-5.962, getPostContingencyResult(result, "contingency5").getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
    }

    @Test
    void testWithTransformerVoltageControl() {
        Network network = VoltageControlNetworkFactory.createWithTransformerSharedRemoteControl();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("T2wT2"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL_1");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        lfParameters.setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL);
        lfParameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
    }

    @Test
    void testWithTwoVoltageControls() {
        Network network = VoltageControlNetworkFactory.createWithTwoVoltageControls();
        List<Contingency> contingencies = List.of(new Contingency("contingency",
                List.of(new BranchContingency("l12"), new BranchContingency("l13"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "b5_vl");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        assertDoesNotThrow(() -> {
            runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        });
    }

    @Test
    void testWithShuntAndGeneratorVoltageControls() {
        Network network = VoltageControlNetworkFactory.createWithShuntAndGeneratorVoltageControl();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("l12"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "b1_vl");
        lfParameters.setShuntCompensatorVoltageControlOn(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Collections.emptySet(), Collections.singleton("b1_vl"), Collections.emptySet()));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(0.99044, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b1").getV(), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testWithShuntAndGeneratorVoltageControls2() {
        Network network = VoltageControlNetworkFactory.createNetworkWith2T2wt();
        network.getTwoWindingsTransformer("T2wT1").getRatioTapChanger()
                .setTargetDeadband(6.0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(network.getTwoWindingsTransformer("T2wT1").getTerminal1())
                .setTargetV(130.0);
        network.getTwoWindingsTransformer("T2wT2").getRatioTapChanger()
                .setTargetDeadband(6.0)
                .setRegulating(true)
                .setTapPosition(0)
                .setRegulationTerminal(network.getTwoWindingsTransformer("T2wT2").getTerminal1())
                .setTargetV(130.0);
        network.getGenerator("GEN_1").setRegulatingTerminal(network.getLine("LINE_12").getTerminal2());
        network.getVoltageLevel("VL_3").newGenerator()
                .setId("GEN_3")
                .setBus("BUS_3")
                .setMinP(0.0)
                .setMaxP(140)
                .setTargetP(0)
                .setTargetV(33)
                .setVoltageRegulatorOn(true)
                .add();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("LINE_12"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL_3");
        lfParameters.setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.AFTER_GENERATOR_VOLTAGE_CONTROL);
        openLoadFlowParameters.setMinRealisticVoltage(0.0);
        openLoadFlowParameters.setMaxRealisticVoltage(3.0);
        lfParameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Collections.emptySet(), Collections.singleton("VL_2"), Collections.emptySet()));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(148.396, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("BUS_2").getV(), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testUpdateReactiveKeysAfterGeneratorContingency() {
        // g1 and g1Bis at b1
        Network network = VoltageControlNetworkFactory.createFourBusNetworkWithSharedVoltageControl();
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l12", "l34"), Set.of("b4_vl"), Collections.emptySet()));
        List<Contingency> contingencies = List.of(new Contingency("g1", List.of(new GeneratorContingency("g1"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        LoadFlow.run(network, lfParameters);
        assertVoltageEquals(1.2, network.getBusBreakerView().getBus("b4"));
        assertReactivePowerEquals(1.372, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-1.814, network.getLine("l12").getTerminal2());

        network.getGenerator("g1").getTerminal().disconnect();
        LoadFlow.run(network, lfParameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES));
        assertVoltageEquals(1.2, network.getBusBreakerView().getBus("b4"));
        assertReactivePowerEquals(1.511, network.getLine("l34").getTerminal2());
        assertReactivePowerEquals(-1.517, network.getLine("l12").getTerminal2());
        network.getGenerator("g1").getTerminal().connect();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre contingency
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(1.2, preContingencyResult.getNetworkResult().getBusResult("b4").getV(), DELTA_V);
        assertEquals(1.372, preContingencyResult.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-1.814, preContingencyResult.getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
        // post contingency: g1 is off
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(1.2, postContingencyResult.getNetworkResult().getBusResult("b4").getV(), DELTA_V);
        assertEquals(1.511, postContingencyResult.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(-1.517, postContingencyResult.getNetworkResult().getBranchResult("l12").getQ2(), DELTA_POWER);
    }

    @Test
    void testWithTieLineContingency() {
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters().setCreateResultExtension(true));
        testWithTieLineContingency(securityAnalysisParameters);
    }

    @Test
    void testWithTieLineContingencyAreaInterchangeControl() {
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters().setCreateResultExtension(true));
        OpenLoadFlowParameters.create(securityAnalysisParameters.getLoadFlowParameters())
                .setAreaInterchangeControl(true);
        testWithTieLineContingency(securityAnalysisParameters);
    }

    void testWithTieLineContingency(SecurityAnalysisParameters securityAnalysisParameters) {
        Network network = BoundaryFactory.createWithTieLine();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new TieLineContingency("t12"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(400.0, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b4").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(400.0, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b3").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(-0.0038, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("l34").getQ2(), LoadFlowAssert.DELTA_POWER);

        OlfBranchResult tieLineResultExt = result.getPreContingencyResult().getNetworkResult().getBranchResult("t12").getExtension(OlfBranchResult.class);
        assertEquals(400.0, tieLineResultExt.getV1(), DELTA_V);
        assertEquals(399.999, tieLineResultExt.getV2(), DELTA_V);
        assertEquals(0.002256, tieLineResultExt.getAngle1(), DELTA_ANGLE);
        assertEquals(0.0, tieLineResultExt.getAngle2(), DELTA_ANGLE);

        Set<String> allBranchIds = network.getDanglingLineStream(DanglingLineFilter.PAIRED).map(Identifiable::getId).collect(Collectors.toSet());
        List<StateMonitor> monitors2 = List.of(new StateMonitor(ContingencyContext.all(), allBranchIds, Collections.emptySet(), Collections.emptySet()));
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, monitors2, securityAnalysisParameters);
        BranchResult dl1Result = result2.getPreContingencyResult().getNetworkResult().getBranchResult("h1");
        assertEquals(35.0, dl1Result.getP1(), DELTA_POWER);
        assertEquals(Double.NaN, dl1Result.getP2());
        BranchResult dl2Result = result2.getPreContingencyResult().getNetworkResult().getBranchResult("h2");
        assertEquals(-35.0, dl2Result.getP1(), DELTA_POWER);
        assertEquals(Double.NaN, dl2Result.getP2());
    }

    @Test
    void testDuplicatedNetworkResultsIssueWithTieLineContingency() {
        Network network = BoundaryFactory.createWithTieLine();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, new OpenSecurityAnalysisParameters().setCreateResultExtension(true));
        Set<String> allBranchIds = network.getDanglingLineStream(DanglingLineFilter.PAIRED).map(Identifiable::getId).collect(Collectors.toSet());
        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), allBranchIds, Collections.emptySet(), Collections.emptySet()));
        List<Contingency> contingencies = List.of(Contingency.branch("l34"));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(List.of("t12", "h1", "h2"), result.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().stream().map(BranchResult::getBranchId).toList());
        Set<String> allBranchIds2 = Set.of("t12", "h1");
        List<StateMonitor> monitors2 = List.of(new StateMonitor(ContingencyContext.all(), allBranchIds2, Collections.emptySet(), Collections.emptySet()));
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, monitors2, securityAnalysisParameters);
        assertEquals(List.of("t12", "h1", "h2"), result2.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().stream().map(BranchResult::getBranchId).toList());
    }

    @Test
    void testWithTieLineContingency2() {
        // using one of the two dangling line ids.
        Network network = BoundaryFactory.createWithTieLine();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new DanglingLineContingency("h1"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(400.0, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b4").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(400.0, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b3").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(-0.0038, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("l34").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithTieLineContingency3() {
        Network network = BoundaryFactory.createWithTieLine();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new GeneratorContingency("g1"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(400.0, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b4").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(399.875, result.getPostContingencyResults().get(0).getNetworkResult().getBusResult("b3").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(50.006, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("t12").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testWithTieLineContingency4(boolean dcFastMode) {
        Network network = BoundaryFactory.createWithTieLine();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new GeneratorContingency("g1"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setDc(true);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(-50.0, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("t12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("t12").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testAcceptableDurations() {
        testAcceptableDurations(new SecurityAnalysisParameters());
    }

    @Test
    void testAcceptableDurationsAreaInterchangeControl() {
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenLoadFlowParameters.create(securityAnalysisParameters.getLoadFlowParameters())
                .setAreaInterchangeControl(true);
        testAcceptableDurations(securityAnalysisParameters);
    }

    void testAcceptableDurations(SecurityAnalysisParameters securityAnalysisParameters) {
        Network network = EurostagTutorialExample1Factory.createWithTieLine();
        network.getGenerator("GEN").setMaxP(4000).setMinP(-4000);

        TieLine line = network.getTieLine("NHV1_NHV2_1");
        line.newCurrentLimits2()
                .setPermanentLimit(900.0)
                .beginTemporaryLimit()
                    .setName("10'")
                    .setAcceptableDuration(600)
                    .setValue(1000.0)
                .endTemporaryLimit()
                .beginTemporaryLimit()
                    .setName("1'")
                    .setAcceptableDuration(60)
                    .setValue(1100.0)
                .endTemporaryLimit()
                .add();
        TieLine line2 = network.getTieLine("NHV1_NHV2_2");
        line2.newCurrentLimits2()
                .setPermanentLimit(900.0)
                .beginTemporaryLimit()
                    .setName("20'")
                    .setAcceptableDuration(1200)
                    .setValue(1000.0)
                .endTemporaryLimit()
                .beginTemporaryLimit()
                    .setName("N/A")
                    .setAcceptableDuration(60)
                    .setValue(1.7976931348623157E308D)
                .endTemporaryLimit()
                .add();
        ContingenciesProvider contingencies = n -> ImmutableList.of(
                new Contingency("contingency1", new BranchContingency("NHV1_NHV2_1")),
                new Contingency("contingency2", new TieLineContingency("NHV1_NHV2_2")),
                new Contingency("contingency3", new DanglingLineContingency("NHV1_XNODE1")),
                new Contingency("contingency4", new DanglingLineContingency("XNODE2_NHV2")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies.getContingencies(network), Collections.emptyList(), securityAnalysisParameters);

        LimitViolation violation0 = new LimitViolation("NHV1_NHV2_2", null, LimitViolationType.CURRENT, "20'",
                60, 1000.0, 1.0F, 1047.8598237521767, TwoSides.TWO);
        int compare0 = LimitViolations.comparator().compare(violation0, result.getPostContingencyResults().get(0)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare0);

        LimitViolation violation1 = new LimitViolation("NHV1_NHV2_1", null, LimitViolationType.CURRENT, "10'",
                60, 1000.0, 1.0F, 1047.8598237521767, TwoSides.TWO);
        int compare1 = LimitViolations.comparator().compare(violation1, result.getPostContingencyResults().get(1)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare1);

        int compare2 = LimitViolations.comparator().compare(violation0, result.getPostContingencyResults().get(2)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare2); // FIXME line open at one side

        int compare3 = LimitViolations.comparator().compare(violation1, result.getPostContingencyResults().get(3)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare3); // FIXME line open at one side

        line.newCurrentLimits1().setPermanentLimit(900.0).add();
        line2.newCurrentLimits1().setPermanentLimit(900.0).add();
        securityAnalysisParameters.getLoadFlowParameters().setDc(true);
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies.getContingencies(network), Collections.emptyList(), securityAnalysisParameters);

        LimitViolation violation4 = new LimitViolation("NHV1_NHV2_2", null, LimitViolationType.CURRENT, "permanent",
                2147483647, 899.9999999999999, 1.0F, 911.6056881941461, TwoSides.ONE);
        int compare4 = LimitViolations.comparator().compare(violation4, result2.getPostContingencyResults().get(0)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare4);
        LimitViolation violation5 = new LimitViolation("NHV1_NHV2_2", null, LimitViolationType.CURRENT, "permanent",
                1200, 899.9999999999999, 1.0F, 911.6056881941461, TwoSides.TWO);
        int compare5 = LimitViolations.comparator().compare(violation5, result2.getPostContingencyResults().get(0)
                .getLimitViolationsResult().getLimitViolations().get(1));
        assertEquals(0, compare5);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);
        SecurityAnalysisResult result3 = runSecurityAnalysis(network, contingencies.getContingencies(network), Collections.emptyList(), securityAnalysisParameters);
        LimitViolation violation6 = new LimitViolation("NHV1_NHV2_2", null, LimitViolationType.CURRENT, "permanent",
                2147483647, 899.9999999999999, 1.0F, 911.6056881941463, TwoSides.ONE);
        int compare6 = LimitViolations.comparator().compare(violation6, result3.getPostContingencyResults().get(0)
                .getLimitViolationsResult().getLimitViolations().get(0));
        assertEquals(0, compare6);
        LimitViolation violation7 = new LimitViolation("NHV1_NHV2_2", null, LimitViolationType.CURRENT, "permanent",
                1200, 899.9999999999999, 1.0F, 911.6056881941463, TwoSides.TWO);
        int compare7 = LimitViolations.comparator().compare(violation7, result3.getPostContingencyResults().get(0)
                .getLimitViolationsResult().getLimitViolations().get(1));
        assertEquals(0, compare7);
    }

    @Test
    void testWithControlledBranchContingency() {
        // PST 'PS1' regulates flow on terminal 1 of line 'L1'. Test contingency of L1.
        Network network = PhaseControlFactory.createNetworkWithT2wt();
        Line line1 = network.getLine("L1");
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL)
                .setTargetDeadband(1)
                .setRegulating(true)
                .setTapPosition(2)
                .setRegulationTerminal(line1.getTerminal1())
                .setRegulationValue(83);
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new LineContingency("L1"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setPhaseShifterRegulationOn(true);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(100.369, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("PS1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(100.184, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testBusContingency(boolean dcFastMode) {
        Network network = EurostagTutorialExample1Factory.createWithFixedCurrentLimits();
        network.getGenerator("GEN").setMaxP(900).setMinP(0);
        network.getGenerator("GEN2").setMaxP(900).setMinP(0);

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                .setSlackBusesIds(List.of("NGEN"));
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = network.getBusBreakerView().getBusStream()
                .map(bus -> new Contingency(bus.getId(), new BusContingency(bus.getId())))
                .collect(Collectors.toList());

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        NetworkResult preContingencyNetworkResult = result.getPreContingencyResult().getNetworkResult();
        assertEquals(456.769, preContingencyNetworkResult.getBranchResult("NHV1_NHV2_1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(456.769, preContingencyNetworkResult.getBranchResult("NHV1_NHV2_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(15225.632, preContingencyNetworkResult.getBranchResult("NGEN_NHV1").getI1(), LoadFlowAssert.DELTA_I);

        assertEquals(91.606, getPostContingencyResult(result, "NLOAD").getNetworkResult().getBranchResult("NHV1_NHV2_1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(91.606, getPostContingencyResult(result, "NLOAD").getNetworkResult().getBranchResult("NHV1_NHV2_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(3069.452, getPostContingencyResult(result, "NHV2").getNetworkResult().getBranchResult("NGEN_NHV1").getI1(), LoadFlowAssert.DELTA_I);
        // No output for NGEN and NVH1

        lfParameters.setDc(true);
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        NetworkResult preContingencyNetworkResult2 = result2.getPreContingencyResult().getNetworkResult();
        assertEquals(455.803, preContingencyNetworkResult2.getBranchResult("NHV1_NHV2_1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(455.803, preContingencyNetworkResult2.getBranchResult("NHV1_NHV2_2").getI1(), LoadFlowAssert.DELTA_I);

        assertEquals(0.0, getPostContingencyResult(result2, "NLOAD").getNetworkResult().getBranchResult("NHV1_NHV2_1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, getPostContingencyResult(result2, "NLOAD").getNetworkResult().getBranchResult("NHV1_NHV2_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(3069.452, getPostContingencyResult(result, "NHV2").getNetworkResult().getBranchResult("NGEN_NHV1").getI1(), LoadFlowAssert.DELTA_I);
        // No output for NGEN and NVH1
    }

    @Test
    void testBusBarSectionBusResults() {
        var network = NodeBreakerNetworkFactory.create3barsAndJustOneVoltageLevel();
        List<Contingency> contingencies = List.of(new Contingency("C1", new SwitchContingency("C1")),
                new Contingency("C2", new SwitchContingency("C2")));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setSlackBusSelectionMode(SlackBusSelectionMode.NAME).setSlackBusId("VL1_1");
        securityAnalysisParameters.getLoadFlowParameters().addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, ReportNode.NO_OP);
        assertEquals(3, result.getPreContingencyResult().getNetworkResult().getBusResults().size());
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals("BBS1", preContingencyResult.getNetworkResult().getBusResults().get(0).getBusId());
        assertEquals(400.0, preContingencyResult.getNetworkResult().getBusResult("BBS1").getV(), LoadFlowAssert.DELTA_V);
        assertEquals("BBS2", preContingencyResult.getNetworkResult().getBusResults().get(1).getBusId());
        assertEquals(400.0, preContingencyResult.getNetworkResult().getBusResult("BBS2").getV(), LoadFlowAssert.DELTA_V);
        assertEquals("BBS3", preContingencyResult.getNetworkResult().getBusResults().get(2).getBusId());
        assertEquals(400.0, preContingencyResult.getNetworkResult().getBusResult("BBS3").getV(), LoadFlowAssert.DELTA_V);
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "C1");
        assertNull(postContingencyResult.getNetworkResult().getBusResult("BBS1"));
        assertEquals(400.0, postContingencyResult.getNetworkResult().getBusResult("BBS2").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(400.0, postContingencyResult.getNetworkResult().getBusResult("BBS3").getV(), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testWithShuntVoltageControlContingency() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getGenerator("g1").setRegulatingTerminal(network.getLoad("l4").getTerminal()).setTargetV(390);
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("tr2"), new BranchContingency("tr3"))));
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setShuntCompensatorVoltageControlOn(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters()
                .setLoadFlowParameters(lfParameters);
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(390.0, preContingencyResult.getNetworkResult().getBusResult("b4").getV(), 0.001);
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "contingency");
        assertEquals(390.0, postContingencyResult.getNetworkResult().getBusResult("b4").getV(), 0.001);
    }

    @Test
    void testVoltageAngleLimit() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        Line line = network.getLine("NHV1_NHV2_1");
        network.newVoltageAngleLimit()
                .setId("val")
                .from(line.getTerminal1())
                .to(line.getTerminal2())
                .setHighLimit(7.0)
                .setLowLimit(-7.0)
                .add();
        loadFlowRunner.run(network);
        assertAngleEquals(0.0, line.getTerminal1().getBusView().getBus());
        assertAngleEquals(-3.506358, line.getTerminal2().getBusView().getBus());
        network.getLine("NHV1_NHV2_2").getTerminal1().disconnect();
        network.getLine("NHV1_NHV2_2").getTerminal2().disconnect();
        loadFlowRunner.run(network);
        assertAngleEquals(0.0, line.getTerminal1().getBusView().getBus());
        assertAngleEquals(-7.499212, line.getTerminal2().getBusView().getBus());

        network.getLine("NHV1_NHV2_2").getTerminal1().connect();
        network.getLine("NHV1_NHV2_2").getTerminal2().connect();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("NHV1_NHV2_2"))));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), new SecurityAnalysisParameters());
        LimitViolation limit = result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().get(0);
        assertEquals(LimitViolationType.LOW_VOLTAGE_ANGLE, limit.getLimitType());
        assertEquals(-7.499212, limit.getValue(), DELTA_ANGLE);

        network.getVoltageAngleLimit("val").remove();
        network.newVoltageAngleLimit()
                .setId("val")
                .from(line.getTerminal2())
                .to(line.getTerminal1())
                .setHighLimit(7.0)
                .setLowLimit(-7.0)
                .add();
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, Collections.emptyList(), new SecurityAnalysisParameters());
        LimitViolation limit2 = result2.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().get(0);
        assertEquals(LimitViolationType.HIGH_VOLTAGE_ANGLE, limit2.getLimitType());
        assertEquals(7.499212, limit2.getValue(), DELTA_ANGLE);

        network.getVoltageAngleLimit("val").remove();
        network.newVoltageAngleLimit()
                .setId("val")
                .from(line.getTerminal2())
                .to(line.getTerminal1())
                .setLowLimit(-7.0)
                .add();
        SecurityAnalysisResult result3 = runSecurityAnalysis(network, contingencies, Collections.emptyList(), new SecurityAnalysisParameters());
        assertTrue(result3.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().isEmpty());
    }

    @Test
    void testMultipleVoltageViolationsSameVoltageLevelIssue() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        VoltageLevel vlload = network.getVoltageLevel("VLLOAD");
        vlload.getBusBreakerView().newBus()
                .setId("NLOAD2")
                .add();
        vlload.newLoad()
                .setId("LOAD2")
                .setBus("NLOAD2")
                .setP0(1)
                .setQ0(1)
                .add();
        network.newLine()
                .setId("L")
                .setVoltageLevel1("VLLOAD")
                .setBus1("NLOAD")
                .setVoltageLevel2("VLLOAD")
                .setBus2("NLOAD2")
                .setR(0)
                .setX(0.01)
                .add();
        vlload.setLowVoltageLimit(140)
                .setHighVoltageLimit(150);
        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_2", List.of(new BranchContingency("NHV1_NHV2_2"))));
        List<StateMonitor> stateMonitors = List.of(new StateMonitor(ContingencyContext.all(),
                                                                    Collections.emptySet(),
                                                                    Set.of("VLLOAD"),
                                                                    Collections.emptySet()));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, stateMonitors, new SecurityAnalysisParameters());
        assertEquals(1, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        BusResult nloadResult = postContingencyResult.getNetworkResult().getBusResult("NLOAD");
        BusResult nload2Result = postContingencyResult.getNetworkResult().getBusResult("NLOAD2");
        assertEquals(137.601, nloadResult.getV(), DELTA_V);
        assertEquals(137.601, nload2Result.getV(), DELTA_V);
        assertEquals(2, postContingencyResult.getLimitViolationsResult().getLimitViolations().size());
        assertEquals("VLLOAD", postContingencyResult.getLimitViolationsResult().getLimitViolations().get(0).getSubjectId());
        assertEquals("VLLOAD", postContingencyResult.getLimitViolationsResult().getLimitViolations().get(1).getSubjectId());

        List<LimitViolation> limitViolations = result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations();
        assertEquals(2, limitViolations.size());
        BusBreakerViolationLocation busBreakerViolationLocation1 = (BusBreakerViolationLocation) limitViolations.get(0).getViolationLocation().get();
        BusBreakerViolationLocation busBreakerViolationLocation2 = (BusBreakerViolationLocation) limitViolations.get(1).getViolationLocation().get();
        assertEquals(busBreakerViolationLocation1.getBusIds(), List.of("NLOAD"));
        assertEquals(busBreakerViolationLocation2.getBusIds(), List.of("NLOAD2"));
    }

    @Test
    void testDoesNotThrowIfSlackDistributionFailure() {
        Network network = DistributedSlackNetworkFactory.create();
        Load l1 = network.getLoad("l1");
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters).setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.THROW);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters()
                .setLoadFlowParameters(lfParameters);
        List<Contingency> contingencies = List.of(new Contingency("l1", List.of(new LoadContingency("l1"))));

        LoadFlowResult lfResult = loadFlowRunner.run(network, lfParameters);
        assertTrue(lfResult.isFullyConverged());

        l1.getTerminal().disconnect();
        // l1 is only load, cannot distribute slack due to generators Pmin
        CompletionException thrownLf = assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, lfParameters));
        assertTrue(thrownLf.getCause().getMessage().startsWith("Failed to distribute slack bus active power mismatch, "));

        CompletionException thrownSa = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters));
        assertTrue(thrownSa.getCause().getMessage().startsWith("Failed to distribute slack bus active power mismatch, "));

        // restore the load l1, now try in SA. Basecase is OK, contingency case should not throw and just flag as non converged.
        l1.getTerminal().connect();
        SecurityAnalysisResult saResult = runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        assertEquals(1, saResult.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = saResult.getPostContingencyResults().get(0);
        assertEquals(PostContingencyComputationStatus.FAILED, postContingencyResult.getStatus());
        assertTrue(postContingencyResult.getConnectivityResult().getDisconnectedElements().contains("l1"));
        assertEquals(600., postContingencyResult.getConnectivityResult().getDisconnectedLoadActivePower(), 1e-6);

        // check OLF parameters weren't modified to reach this
        assertEquals(OpenLoadFlowParameters.SlackDistributionFailureBehavior.THROW, OpenLoadFlowParameters.get(lfParameters).getSlackDistributionFailureBehavior());
    }

    @Test
    void testIncrementalTransformerVoltageControlWithSwitchContingency() {
        Network network = VoltageControlNetworkFactory.createNetworkWith2T2wtAndSwitch();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setTransformerVoltageControlOn(true);
        parameters.setDistributedSlack(false);
        parameters.setUseReactiveLimits(true);
        OpenLoadFlowParameters.create(parameters)
                .setTransformerVoltageControlMode(OpenLoadFlowParameters.TransformerVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL);
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        TwoWindingsTransformer t2wt = network.getTwoWindingsTransformer("T2wT");
        t2wt.getRatioTapChanger()
                .setTargetDeadband(2)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt.getTerminal2())
                .setTargetV(33.0);
        TwoWindingsTransformer t2wt2 = network.getTwoWindingsTransformer("T2wT2");
        t2wt2.getRatioTapChanger()
                .setTargetDeadband(2)
                .setRegulating(true)
                .setTapPosition(1)
                .setRegulationTerminal(t2wt2.getTerminal1())
                .setTargetV(33.0);
        network.getGenerator("GEN_5").newMinMaxReactiveLimits().setMinQ(-5.0).setMaxQ(5.0).add();
        List<Contingency> contingencies = List.of(new Contingency("c", new SwitchContingency("SWITCH")));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        // Check a violation location in breaker mode
        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        LimitViolation lv = result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().get(0);
        assertEquals(LimitViolationType.HIGH_VOLTAGE, lv.getLimitType());
        assertEquals(List.of(network.getBusBreakerView().getBus("BUS_5")), lv.getViolationLocation().map(l -> l.getBusBreakerView(network).getBusStream().toList()).orElse(Collections.emptyList()));

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "c");
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(33.824, result.getPreContingencyResult().getNetworkResult().getBusResult("BUS_3").getV(), DELTA_V);
        assertEquals(32.605, postContingencyResult.getNetworkResult().getBusResult("BUS_3").getV(), DELTA_V);
    }

    @Test
    void testIncrementalShuntVoltageControlWithSwitchContingency() {
        Network network = ShuntNetworkFactory.createWithGeneratorAndShuntNonImpedant();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setShuntCompensatorVoltageControlOn(true);
        OpenLoadFlowParameters.create(parameters)
                .setShuntVoltageControlMode(OpenLoadFlowParameters.ShuntVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL);
        ShuntCompensator shunt = network.getShuntCompensator("SHUNT");
        shunt.setTargetDeadband(2);
        ShuntCompensator shunt2 = network.getShuntCompensator("SHUNT2");
        Bus b3 = network.getBusBreakerView().getBus("b3");
        Generator g2 = network.getGenerator("g2");
        network.getGenerator("g2").newMinMaxReactiveLimits().setMinQ(-150).setMaxQ(150).add();

        // Generator reactive capability is not enough to hold voltage alone but with shunt it is ok
        shunt.setVoltageRegulatorOn(true);
        shunt2.setVoltageRegulatorOn(true);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertVoltageEquals(393, b3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(0, shunt2.getSectionCount());
        assertReactivePowerEquals(-134.585, g2.getTerminal());

        // Both shunts are used at generator targetV
        g2.setTargetV(395);
        shunt.setSectionCount(0);
        LoadFlowResult result2 = loadFlowRunner.run(network, parameters);
        assertTrue(result2.isFullyConverged());
        assertVoltageEquals(395, b3);
        assertEquals(1, shunt.getSectionCount());
        assertEquals(1, shunt2.getSectionCount());
        assertReactivePowerEquals(-110.176, g2.getTerminal());

        shunt.setSectionCount(0);
        shunt2.setSectionCount(0);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        Contingency c = new Contingency("c", new SwitchContingency("switch"));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult saResult = runSecurityAnalysis(network, List.of(c), monitors, securityAnalysisParameters);
        PostContingencyResult postContingencyResult = getPostContingencyResult(saResult, "c");
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        assertEquals(395, saResult.getPreContingencyResult().getNetworkResult().getBusResult("b3").getV());
        assertEquals(393.23, postContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V);
    }

    @Test
    void testPermanentLimitName() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getLine("NHV1_NHV2_1").newCurrentLimits1()
                .setPermanentLimit(300)
                .add();
        SecurityAnalysisResult result = runSecurityAnalysis(network);
        List<LimitViolation> limitViolations = result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations();
        assertEquals(1, limitViolations.size());
        assertEquals(LimitViolationUtils.PERMANENT_LIMIT_NAME, limitViolations.get(0).getLimitName());
    }

    /**
     *                   G
     *              C    |
     * BBS1 -------[+]------- BBS2     VL1
     *    NBR [+]       [+] B1
     *         |         |
     *     L1  |         | L2
     *         |         |
     *     B3 [+]       [+] B4
     * BBS3 -----------------          VL2
     *             |
     *             LD
     * @return
     */
    private static Network createNodeBreakerNetworkForTestingLineOpenOneSide() {
        Network network = createNodeBreakerNetwork();
        VoltageLevel vl1 = network.getVoltageLevel("VL1");
        vl1.getNodeBreakerView().removeInternalConnections(0, 5);
        vl1.getNodeBreakerView().newBreaker()
                .setId("NBR")
                .setNode1(0)
                .setNode2(5)
                .setOpen(false)
                .add();
        return network;
    }

    @Test
    void testLineOpenOneSideContingency() {
        Network network = createNodeBreakerNetworkForTestingLineOpenOneSide();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        Line l1 = network.getLine("L1");
        l1.getTerminal1().disconnect();
        LoadFlowResult lfResult = runLoadFlow(network, lfParameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, lfResult.getComponentResults().get(0).getStatus());
        double p1 = l1.getTerminal1().getP();
        double q1 = l1.getTerminal1().getQ();
        double p2 = l1.getTerminal2().getP();
        double q2 = l1.getTerminal2().getQ();
        assertEquals(0, p1, 0);
        assertEquals(0, q1, 0);
        assertEquals(0.016, p2, DELTA_POWER);
        assertEquals(-55.873, q2, DELTA_POWER);

        network = createNodeBreakerNetworkForTestingLineOpenOneSide();
        List<Contingency> contingencies = List.of(Contingency.busbarSection("BBS1"));

        List<StateMonitor> stateMonitors = List.of(new StateMonitor(ContingencyContext.all(),
                                                                    Set.of("L1"),
                                                                    Collections.emptySet(),
                                                                    Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, stateMonitors, lfParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(1, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        BranchResult l1Result = postContingencyResult.getNetworkResult().getBranchResult("L1");
        assertNotNull(l1Result);
        assertEquals(0, l1Result.getP1(), DELTA_POWER);
        assertEquals(0, l1Result.getQ1(), DELTA_POWER);
        assertEquals(p2, l1Result.getP2(), DELTA_POWER);
        assertEquals(q2, l1Result.getQ2(), DELTA_POWER);
    }

    @Test
    void testLineOpenOneSideContingencyBusBreaker() {
        Network network = IeeeCdfNetworkFactory.create14();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_0");

        Line l23 = network.getLine("L2-3-1");
        Line l34 = network.getLine("L3-4-1");
        l23.getTerminal2().disconnect();
        l34.getTerminal1().disconnect();
        LoadFlowResult lfResult = runLoadFlow(network, lfParameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, lfResult.getComponentResults().get(0).getStatus());
        double l23p1 = l23.getTerminal1().getP();
        double l23q1 = l23.getTerminal1().getQ();
        double l23p2 = l23.getTerminal2().getP();
        double l23q2 = l23.getTerminal2().getQ();
        double l34p1 = l34.getTerminal1().getP();
        double l34q1 = l34.getTerminal1().getQ();
        double l34p2 = l34.getTerminal2().getP();
        double l34q2 = l34.getTerminal2().getQ();
        assertEquals(0.002, l23p1, DELTA_POWER);
        assertEquals(-4.793, l23q1, DELTA_POWER);
        assertEquals(0, l23p2, 0);
        assertEquals(0, l23q2, 0);
        assertEquals(0, l34p1, 0);
        assertEquals(0, l34q1, 0);
        assertEquals(0, l34p2, DELTA_POWER);
        assertEquals(-1.334, l34q2, DELTA_POWER);

        l23.getTerminal2().connect();
        l34.getTerminal1().connect();

        List<Contingency> contingencies = List.of(Contingency.bus("B3"));

        List<StateMonitor> stateMonitors = List.of(new StateMonitor(ContingencyContext.all(),
                                                                    Set.of("L2-3-1", "L3-4-1"),
                                                                    Collections.emptySet(),
                                                                    Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, stateMonitors, lfParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(1, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        BranchResult l23Result = postContingencyResult.getNetworkResult().getBranchResult("L2-3-1");
        BranchResult l34Result = postContingencyResult.getNetworkResult().getBranchResult("L3-4-1");
        assertNotNull(l23Result);
        assertNotNull(l34Result);
        assertEquals(l23p1, l23Result.getP1(), DELTA_POWER);
        assertEquals(l23q1, l23Result.getQ1(), DELTA_POWER);
        assertEquals(l23p2, l23Result.getP2(), DELTA_POWER);
        assertEquals(l23q2, l23Result.getQ2(), DELTA_POWER);
        assertEquals(l34p1, l34Result.getP1(), DELTA_POWER);
        assertEquals(l34q1, l34Result.getQ1(), DELTA_POWER);
        assertEquals(l34p2, l34Result.getP2(), DELTA_POWER);
        assertEquals(-1.334, l34Result.getQ2(), DELTA_POWER); // ????
    }

    @Test
    void testBusContingencyWithOpenLinesConnectedToLostBus() {
        Network network = BusContingencyOpenLinesNetworkFactory.create();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "b1");

        LoadFlowResult lfResult = runLoadFlow(network, lfParameters);
        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, lfResult.getComponentResults().get(0).getStatus());

        List<Contingency> contingencies = List.of(Contingency.bus("b3"));

        List<StateMonitor> stateMonitors = Collections.emptyList();

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, stateMonitors, lfParameters);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(1, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
    }

    @Test
    void testIssueWithReactiveTerms() {
        Network network = FourBusNetworkFactory.createWithAdditionalReactiveTerms();
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                .setSlackBusesIds(List.of("b3"));
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setContingencyPropagation(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(new Contingency("b1", new BusContingency("b1")));
        contingencies.add(new Contingency("b2", new BusContingency("b2")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(0.0858, preContingencyResult.getNetworkResult().getBranchResult("l24").getQ2(), DELTA_POWER);
        assertEquals(-0.0859, preContingencyResult.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(1, preContingencyResult.getNetworkResult().getBusResult("b4").getV(), DELTA_V);
        assertEquals(1.06, preContingencyResult.getNetworkResult().getBusResult("b2").getV(), DELTA_V);
        assertEquals(1.21, preContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V);
        assertEquals(0.966, preContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V);
        // b1 contingency
        PostContingencyResult b1PostContingencyResult = getPostContingencyResult(result, "b1");
        assertEquals(0.0, b1PostContingencyResult.getNetworkResult().getBranchResult("l24").getQ2(), DELTA_POWER);
        assertEquals(0.0, b1PostContingencyResult.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(1, b1PostContingencyResult.getNetworkResult().getBusResult("b4").getV(), DELTA_V);
        assertEquals(1.802, b1PostContingencyResult.getNetworkResult().getBusResult("b2").getV(), DELTA_V);
        assertEquals(1.802, b1PostContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V);
        // b2 contingency
        PostContingencyResult b2PostContingencyResult = getPostContingencyResult(result, "b2");
        assertEquals(0.0, b2PostContingencyResult.getNetworkResult().getBranchResult("l24").getQ2(), DELTA_POWER);
        assertEquals(0.0, b2PostContingencyResult.getNetworkResult().getBranchResult("l34").getQ2(), DELTA_POWER);
        assertEquals(1, b2PostContingencyResult.getNetworkResult().getBusResult("b4").getV(), DELTA_V);
        assertEquals(1, b2PostContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V);
        assertEquals(0.795, b2PostContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V);
    }

    /**
     *
     * g0 (regulate b1)                     g4
     * |                                    | t34 (regulate b3)
     * b0 ----- b1 ===== b2 ===== b3 --OO-- b4
     *          |                 |
     *           ------- b5 ------
     *                   |
     *                   ld5
     */
    @Test
    void testTransformerTargetVoltagePrioritiesWithContingency() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();

        LoadFlowParameters loadFlowParameters = new LoadFlowParameters()
                .setDistributedSlack(false)
                .setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(loadFlowParameters)
                .setVoltageTargetPriorities(List.of("TRANSFORMER"));
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters()
                .setLoadFlowParameters(loadFlowParameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters()
                .setCreateResultExtension(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(new Contingency("contingency1", new BranchContingency("l01")));
        contingencies.add(new Contingency("contingency2", new BranchContingency("l23")));

        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre-contingency verification
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, preContingencyResult.getStatus());
        assertEquals(1.1, preContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1 (with tr34 targetV)
        assertEquals(1.1, preContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // ... also b3 through zero impedance network

        // post-contingency verification: l01 contingency disconnects g0
        PostContingencyResult postContingencyResult0 = getPostContingencyResult(result, "contingency1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult0.getStatus());
        assertEquals(1.131, postContingencyResult0.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(1.131, postContingencyResult0.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // ... also b1 through zero impedance network
        assertEquals(0.9182049, postContingencyResult0.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // post-contingency verification: l23 contingency breaks the zero impedance network
        PostContingencyResult postContingencyResult1 = getPostContingencyResult(result, "contingency2");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult1.getStatus());
        assertEquals(1.000, postContingencyResult1.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1
        assertEquals(1.087, postContingencyResult1.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(0.8755940, postContingencyResult1.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // Verify same results are obtained with l23 initially disconnected and an operator strategy reconnecting it
        network.getLine("l23").disconnect();
        List<Action> actions = List.of(new TerminalsConnectionAction("close_l23", "l23", false),
                                       new TerminalsConnectionAction("close_l01", "l01", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("contingency1"), new TrueCondition(), List.of("close_l23", "close_l01")));
        result = runSecurityAnalysis(network, contingencies,
                monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP);

        // pre-contingency verification
        preContingencyResult = result.getPreContingencyResult();
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, preContingencyResult.getStatus());
        assertEquals(1.000, preContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1
        assertEquals(1.087, preContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3

        // post-contingency verification: l01 contingency prevents g0 to hold voltage at b1, tr34 holds voltage at b3
        postContingencyResult0 = getPostContingencyResult(result, "contingency1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult0.getStatus());
        assertEquals(1.043, postContingencyResult0.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // voltage is not held at b1
        assertEquals(1.111, postContingencyResult0.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(0.9066748, postContingencyResult0.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // post-operator strategy verification: applied action merges the zero impedance networks, target voltage of tr34 is applied.
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, operatorStrategyResult.getStatus());
        assertEquals(1.1, operatorStrategyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // g0 is controlling voltage of b3
        assertEquals(1.1, operatorStrategyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // with tr34 targetV
        assertEquals(0.9066748, operatorStrategyResult.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);
    }

    /**
     *
     * g0 (regulate b1)                     g4
     * |                                    | t34 (regulate b3)
     * b0 ----- b1 ===== b2 ===== b3 --OO-- b4
     *          |                 |
     *           ------- b5 ------
     *                   |
     *                   ld5
     */
    @Test
    void testDefaultTargetVoltagePrioritiesWithContingency() {
        Network network = ZeroImpedanceNetworkFactory.createWithVoltageControl();

        LoadFlowParameters loadFlowParameters = new LoadFlowParameters()
                .setDistributedSlack(false)
                .setTransformerVoltageControlOn(true);
        OpenLoadFlowParameters.create(loadFlowParameters);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters()
                .setLoadFlowParameters(loadFlowParameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters()
                .setCreateResultExtension(true);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(new Contingency("contingency1", new BranchContingency("l01")));
        contingencies.add(new Contingency("contingency2", new BranchContingency("l23")));

        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        // pre-contingency verification
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, preContingencyResult.getStatus());
        assertEquals(1.0, preContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1 (with own targetV)
        assertEquals(1.0, preContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // ... also b3 through zero impedance network

        // post-contingency verification: l01 contingency disconnects g0
        PostContingencyResult postContingencyResult0 = getPostContingencyResult(result, "contingency1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult0.getStatus());
        assertEquals(1.131, postContingencyResult0.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(1.131, postContingencyResult0.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // ... also b1 through zero impedance network
        assertEquals(0.9183037, postContingencyResult0.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // post-contingency verification: l23 contingency breaks the zero impedance network
        PostContingencyResult postContingencyResult1 = getPostContingencyResult(result, "contingency2");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult1.getStatus());
        assertEquals(1.000, postContingencyResult1.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1
        assertEquals(1.087, postContingencyResult1.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(0.8751061, postContingencyResult1.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // Verify same results are obtained with l23 initially disconnected and an operator strategy reconnecting it
        network.getLine("l23").disconnect();
        List<Action> actions = List.of(new TerminalsConnectionAction("close_l23", "l23", false),
                new TerminalsConnectionAction("close_l01", "l01", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("contingency1"), new TrueCondition(), List.of("close_l23", "close_l01")));
        result = runSecurityAnalysis(network, List.of(new Contingency("contingency1", new BranchContingency("l01"))),
                monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP);

        // pre-contingency verification
        preContingencyResult = result.getPreContingencyResult();
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, preContingencyResult.getStatus());
        assertEquals(1.000, preContingencyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // g0 is controlling voltage of b1
        assertEquals(1.087, preContingencyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3

        // post-contingency verification: l01 contingency prevents g0 to hold voltage at b1, tr34 holds voltage at b3
        postContingencyResult0 = getPostContingencyResult(result, "contingency1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, postContingencyResult0.getStatus());
        assertEquals(1.043, postContingencyResult0.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // voltage is not held at b1
        assertEquals(1.111, postContingencyResult0.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // tr34 is controlling voltage of b3
        assertEquals(0.9066748, postContingencyResult0.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);

        // post-operator strategy verification: applied action merges the zero impedance networks, target voltage of g0 is applied.
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy1");
        assertEquals(PostContingencyComputationStatus.CONVERGED, operatorStrategyResult.getStatus());
        assertEquals(1.0, operatorStrategyResult.getNetworkResult().getBusResult("b3").getV(), DELTA_V); // g0 is controlling voltage of b3
        assertEquals(1.0, operatorStrategyResult.getNetworkResult().getBusResult("b1").getV(), DELTA_V); // ... also b1 through zero impedance network
        assertEquals(0.9066748, operatorStrategyResult.getNetworkResult().getBranchResult("tr34").getExtension(OlfBranchResult.class).getContinuousR1(), DELTA_RHO);
    }

    @Test
    void testSlackBusRelocation() {
        Network network = createNodeBreakerNetwork();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_0");

        List<Contingency> contingencies = List.of(
            Contingency.busbarSection("BBS1"),
            Contingency.busbarSection("BBS3"),
            Contingency.line("L1")
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);
        assertEquals(3, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(2).getStatus());
    }

    @Test
    void testSlackBusRelocationAndMultipleSlackBuses() {
        // should give the same result as with one slack bus because not supported and forced to one
        Network network = createNodeBreakerNetwork();
        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(lfParameters)
                .setMaxSlackBusCount(2);

        List<Contingency> contingencies = List.of(
                Contingency.busbarSection("BBS1"),
                Contingency.busbarSection("BBS3"),
                Contingency.line("L1")
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);
        assertEquals(3, result.getPostContingencyResults().size());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertSame(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(2).getStatus());
    }

    @Test
    void testLimitReductions() {
        Network network = createNodeBreakerNetwork();
        List<Contingency> contingencies = List.of(new Contingency("L2", new BranchContingency("L2")));
        List<StateMonitor> monitors = createNetworkMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(1, result.getPostContingencyResults().size());
        PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        List<LimitViolation> limitViolations = postContingencyResult.getLimitViolationsResult().getLimitViolations();
        assertEquals(2, limitViolations.size());
        assertEquals("L1", limitViolations.get(0).getSubjectId());
        assertEquals("permanent", limitViolations.get(0).getLimitName());
        assertEquals(60, limitViolations.get(0).getAcceptableDuration());
        assertEquals(ThreeSides.ONE, limitViolations.get(0).getSide());
        assertEquals(1., limitViolations.get(0).getLimitReduction(), 0.0001);
        assertEquals(940., limitViolations.get(0).getLimit(), 0.0001);
        assertEquals(945.51416, limitViolations.get(0).getValue(), 0.0001);

        LimitReduction limitReduction1 = LimitReduction.builder(LimitType.CURRENT, 0.9)
                .withNetworkElementCriteria(
                        new IdentifiableCriterion(new AtLeastOneNominalVoltageCriterion(VoltageInterval.between(380., 410., true, true))),
                        new IdentifiableCriterion(new AtLeastOneNominalVoltageCriterion(VoltageInterval.between(220., 240., true, true))))
                .withLimitDurationCriteria(IntervalTemporaryDurationCriterion.between(0, 300, true, false))
                .build();
        LimitReduction limitReduction2 = LimitReduction.builder(LimitType.CURRENT, 0.95)
                .withNetworkElementCriteria(
                        new IdentifiableCriterion(new AtLeastOneNominalVoltageCriterion(VoltageInterval.between(380., 410., true, true))),
                        new IdentifiableCriterion(new AtLeastOneNominalVoltageCriterion(VoltageInterval.between(220., 240., true, true))))
                .withLimitDurationCriteria(IntervalTemporaryDurationCriterion.between(300, 600, true, false))
                .build();
        List<LimitReduction> limitReductions = List.of(limitReduction1, limitReduction2);
        result = runSecurityAnalysis(network, contingencies, monitors, limitReductions, new SecurityAnalysisParameters());

        assertSame(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(1, result.getPostContingencyResults().size());
        postContingencyResult = result.getPostContingencyResults().get(0);
        assertSame(PostContingencyComputationStatus.CONVERGED, postContingencyResult.getStatus());
        limitViolations = postContingencyResult.getLimitViolationsResult().getLimitViolations();
        assertEquals(2, limitViolations.size());
        assertEquals("L1", limitViolations.get(0).getSubjectId());
        assertEquals("60", limitViolations.get(0).getLimitName());
        assertEquals(0, limitViolations.get(0).getAcceptableDuration());
        assertEquals(ThreeSides.ONE, limitViolations.get(0).getSide());
        assertEquals(0.9, limitViolations.get(0).getLimitReduction(), 0.0001);
        assertEquals(1000., limitViolations.get(0).getLimit(), 0.0001);
        assertEquals(945.51416, limitViolations.get(0).getValue(), 0.0001);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testPhaseTapChangerContingency(boolean dcFastMode) {
        Network network = PhaseControlFactory.createNetworkWithT2wt();
        TwoWindingsTransformer pst = network.getTwoWindingsTransformer("PS1");
        pst.getPhaseTapChanger().setTapPosition(2);

        network.newLine().setId("L3")
                .setConnectableBus1("B1")
                .setBus1("B1")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        network.newLine().setId("L4")
                .setConnectableBus1("B3")
                .setBus1("B3")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = new OpenSecurityAnalysisParameters();
        openSecurityAnalysisParameters.setDcFastMode(dcFastMode);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, openSecurityAnalysisParameters);

        List<Contingency> contingencies = List.of(Contingency.twoWindingsTransformer("PS1"));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(7.623, preContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(56.503, preContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(7.623, preContingencyResult.getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(28.252, preContingencyResult.getNetworkResult().getBranchResult("L4").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(84.755, preContingencyResult.getNetworkResult().getBranchResult("PS1").getP1(), LoadFlowAssert.DELTA_POWER);
        // post-contingency tests
        PostContingencyResult ps1ContingencyResult = getPostContingencyResult(result, "PS1");
        assertEquals(50.0, ps1ContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, ps1ContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, ps1ContingencyResult.getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, ps1ContingencyResult.getNetworkResult().getBranchResult("L4").getP1(), LoadFlowAssert.DELTA_POWER);

        // we compare with a dc load flow
        pst.getTerminal1().disconnect();
        pst.getTerminal2().disconnect();
        LoadFlow.run(network, lfParameters);
        assertActivePowerEquals(50.0, network.getBranch("L1").getTerminal1());
        assertActivePowerEquals(0.0, network.getBranch("L2").getTerminal1());
        assertActivePowerEquals(50.0, network.getBranch("L3").getTerminal1());
        assertActivePowerEquals(0.0, network.getBranch("L4").getTerminal1());
    }

    @Test
    void testMultiThreads() {
        Network network = createNodeBreakerNetwork();
        assertFalse(network.getVariantManager().isVariantMultiThreadAccessAllowed());

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        OpenSecurityAnalysisParameters securityAnalysisParametersExt = new OpenSecurityAnalysisParameters();
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, securityAnalysisParametersExt);

        List<Contingency> contingencies = Stream.of("L1", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .toList();

        SecurityAnalysisResult resultOneThread = runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        securityAnalysisParametersExt.setThreadCount(2);
        SecurityAnalysisResult resultTwoThreads = runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters);
        assertFalse(network.getVariantManager().isVariantMultiThreadAccessAllowed());
        assertEquals(resultOneThread.getPostContingencyResults().size(), resultTwoThreads.getPostContingencyResults().size());
        assertEquals(resultOneThread.getOperatorStrategyResults().size(), resultTwoThreads.getOperatorStrategyResults().size());
    }

    @Test
    void testMultiThreadsWhenLessContingenciesThanThreads() {
        Network network = createNodeBreakerNetwork();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenSecurityAnalysisParameters securityAnalysisParametersExt = new OpenSecurityAnalysisParameters()
                .setThreadCount(2);
        securityAnalysisParameters.addExtension(OpenSecurityAnalysisParameters.class, securityAnalysisParametersExt);

        List<Contingency> contingencies = Stream.of("L1")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .toList();

        assertDoesNotThrow(() -> runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters));
    }

    @Test
    void testWithFictitiousLoad() {
        testWithFictitiousLoad(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        testWithFictitiousLoad(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);
    }

    private void testWithFictitiousLoad(LoadFlowParameters.BalanceType balanceType) {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getLoad("l1").setFictitious(true); // single load on bus
        network.getLoad("l4").setLoadType(LoadType.FICTITIOUS); // one load amongst many on the bus

        List<Contingency> contingencies = List.of(
                new Contingency("l5", new LoadContingency("l5")),
                new Contingency("l1", new LoadContingency("l1")),
                new Contingency("l4", new LoadContingency("l4")));

        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.all(), Set.of("l14", "l24", "l34"), emptySet(), emptySet())
        );

        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true)
                .setBalanceType(balanceType);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxActivePowerMismatch(1e-3)
                .setMaxReactivePowerMismatch(1e-3);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(3, result.getPostContingencyResults().size());
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(2).getStatus());

        // contingency 0: normal load (not fictitious)
        network.getLoad("l5").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);
        NetworkResult networkResultContingency0 = result.getPostContingencyResults().get(0).getNetworkResult();
        assertEquals(network.getLine("l24").getTerminal1().getP(), networkResultContingency0.getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), networkResultContingency0.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), networkResultContingency0.getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
        network.getLoad("l5").getTerminal().connect();

        // contingency 1: only fictitious load on bus
        network.getLoad("l1").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);
        NetworkResult networkResultContingency1 = result.getPostContingencyResults().get(1).getNetworkResult();
        assertEquals(network.getLine("l24").getTerminal1().getP(), networkResultContingency1.getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), networkResultContingency1.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), networkResultContingency1.getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
        network.getLoad("l1").getTerminal().connect();

        // contingency 2: only fictitious load amongst many on the bus
        network.getLoad("l4").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);
        NetworkResult networkResultContingency2 = result.getPostContingencyResults().get(2).getNetworkResult();
        assertEquals(network.getLine("l24").getTerminal1().getP(), networkResultContingency2.getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), networkResultContingency2.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), networkResultContingency2.getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
        network.getLoad("l4").getTerminal().connect();
    }

    @Test
    void testWithFictitiousLoad2() {
        testWithFictitiousLoad2(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        testWithFictitiousLoad2(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);
    }

    private void testWithFictitiousLoad2(LoadFlowParameters.BalanceType balanceType) {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getLoad("l4").setLoadType(LoadType.FICTITIOUS); // one load amongst many on the bus

        // contigency on two loads on the same bus: one is fictitious and one is not
        List<Contingency> contingencies = List.of(new Contingency("l4_and_l5", List.of(new LoadContingency("l5"), new LoadContingency("l4"))));

        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.all(), Set.of("l14", "l24", "l34"), emptySet(), emptySet())
        );

        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(true)
                .setBalanceType(balanceType);
        OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED)
                .setNewtonRaphsonStoppingCriteriaType(NewtonRaphsonStoppingCriteriaType.PER_EQUATION_TYPE_CRITERIA)
                .setMaxActivePowerMismatch(1e-3)
                .setMaxReactivePowerMismatch(1e-3);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getPreContingencyResult().getStatus());
        assertEquals(1, result.getPostContingencyResults().size());
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(0).getStatus());

        // contingency 0: lost loads l4 (fictitious) and l5 (not fictitious)
        network.getLoad("l4").getTerminal().disconnect();
        network.getLoad("l5").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);
        NetworkResult networkResultContingency0 = result.getPostContingencyResults().get(0).getNetworkResult();
        assertEquals(network.getLine("l24").getTerminal1().getP(), networkResultContingency0.getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), networkResultContingency0.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), networkResultContingency0.getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void multiComponentSaTest() {
        Network network = FourBusNetworkFactory.createWithTwoScs();
        // Add a load on small component
        network.getBusBreakerView().getBus("c1")
                .getVoltageLevel().newLoad()
                .setId("dummyLoad")
                .setBus("c1")
                .setConnectableBus("c1")
                .setP0(1)
                .setQ0(0)
                .add();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.ALL);

        List<Contingency> contingencies = List.of(new Contingency("l13", new BranchContingency("l13")),
                new Contingency("dummyLoad", new LoadContingency("dummyLoad")));

        // Monitor branch in both components
        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "lc12", "lc12Bis"), emptySet(), emptySet())
        );

        SecurityAnalysisResult results = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
        NetworkResult preContingencyResults = results.getPreContingencyResult().getNetworkResult();
        NetworkResult postContingencyResultsl13 = results.getPostContingencyResults().get(0).getNetworkResult();
        NetworkResult postContingencyResultslc12Bis = results.getPostContingencyResults().get(1).getNetworkResult();

        // Result for base case is available for all components
        assertEquals(0.084, preContingencyResults.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.417, preContingencyResults.getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.417, preContingencyResults.getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.493, preContingencyResults.getBranchResult("lc12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.493, preContingencyResults.getBranchResult("lc12Bis").getP1(), LoadFlowAssert.DELTA_POWER);

        // Result for post contingency on l13 is available for part of the network on which the contingency has an impact
        assertEquals(1.021, postContingencyResultsl13.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.311, postContingencyResultsl13.getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.310, postContingencyResultsl13.getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

        // No impact on this component, no results
        assertNull(postContingencyResultsl13.getBranchResult("lc12"));
        assertNull(postContingencyResultsl13.getBranchResult("lc12Bis"));

        // Result for post contingency on dummyLoad is available for part of the network on which the contingency has an impact
        assertEquals(0.498, postContingencyResultslc12Bis.getBranchResult("lc12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.498, postContingencyResultslc12Bis.getBranchResult("lc12Bis").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void multiComponentSaTestContingencyBothComponentsAndOperatorStrategy() {

        Network network = FourBusNetworkFactory.createWithTwoScs();
        // Add a load on small component
        network.getBusBreakerView().getBus("c1")
                .getVoltageLevel().newLoad()
                .setId("dummyLoad")
                .setBus("c1")
                .setConnectableBus("c1")
                .setP0(1)
                .setQ0(0)
                .add();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.ALL);

        // This contingency should impact both components
        List<Contingency> contingencies = List.of(new Contingency("compositeContingency", List.of(new BranchContingency("l13"), new LoadContingency("dummyLoad"))));

        // Monitor branch in both components
        List<StateMonitor> monitors = List.of(
                new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "lc12", "lc12Bis"), emptySet(), emptySet())
        );

        List<Action> actions = List.of(new TerminalsConnectionAction("open_l13", "l13", true),
                new LoadActionBuilder().withId("dc2").withLoadId("dc2").withRelativeValue(false).withActivePowerValue(1).build());
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("compositeContingency"), new TrueCondition(), List.of("open_l13", "dc2")));

        SecurityAnalysisResult results = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP);
        NetworkResult preContingencyResults = results.getPreContingencyResult().getNetworkResult();

        // Result for base case is available for all components
        assertEquals(0.084, preContingencyResults.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.417, preContingencyResults.getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.417, preContingencyResults.getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.493, preContingencyResults.getBranchResult("lc12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.493, preContingencyResults.getBranchResult("lc12Bis").getP1(), LoadFlowAssert.DELTA_POWER);

        NetworkResult postContingencyResults = results.getPostContingencyResults().get(0).getNetworkResult();

        // Because contingency impact both networks, each simulation should output results for its components
        // Results should then be merged in a single post contingency result
        assertEquals(1.021, postContingencyResults.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.311, postContingencyResults.getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.310, postContingencyResults.getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.498, postContingencyResults.getBranchResult("lc12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.498, postContingencyResults.getBranchResult("lc12Bis").getP1(), LoadFlowAssert.DELTA_POWER);

        NetworkResult operatorStrategyResult = results.getOperatorStrategyResults().get(0).getNetworkResult();

        // Operator strategy is applied on both network and result should be merged and available
        assertEquals(1.022, operatorStrategyResult.getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.311, operatorStrategyResult.getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.311, operatorStrategyResult.getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.499, operatorStrategyResult.getBranchResult("lc12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.499, operatorStrategyResult.getBranchResult("lc12Bis").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    /**
     * FIXME Multiple initial components with an action reconnecting a line linking both components is not well supported
     * This test give an example of inconsistent results for this specific case
     */
    @Test
    void multipleComponentTerminalActionReconnect() {

        Network network = FourBusNetworkFactory.createWithTwoScs();

        // Add a line between two components and disconnect it
        Line lB3C1 = network.newLine()
            .setId("LB3C1")
            .setBus1("b3")
            .setConnectableBus1("b3")
            .setBus2("c1")
            .setConnectableBus2("c1")
            .setR(0.0)
            .setX(0.1)
            .add();
        lB3C1.getTerminal1().disconnect();

        // Add a load on small component
        network.getBusBreakerView().getBus("c1")
            .getVoltageLevel().newLoad()
            .setId("dummyLoad")
            .setBus("c1")
            .setConnectableBus("c1")
            .setP0(1)
            .setQ0(0)
            .add();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.ALL);

        // Dummy contingency
        List<Contingency> contingencies = List.of(new Contingency("contingencyLoad", List.of(new LoadContingency("dummyLoad"))));

        // Monitor branch in both components and line between them
        List<StateMonitor> monitors = List.of(
            new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "LB3C1", "lc12", "lc12Bis"), emptySet(), emptySet())
        );

        // This action will reconnect the two islands
        List<Action> actions = List.of(new TerminalsConnectionAction("close_LB3C1", "LB3C1", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("contingencyLoad"), new TrueCondition(), List.of("close_LB3C1")));

        SecurityAnalysisResult results = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, ReportNode.NO_OP);
        NetworkResult preContingencyResults = results.getPreContingencyResult().getNetworkResult();

        // Result for base case is available for all components
        assertNotNull(preContingencyResults.getBranchResult("l14"));
        assertNotNull(preContingencyResults.getBranchResult("lc12"));
        assertNotNull(preContingencyResults.getBranchResult("LB3C1"));

        NetworkResult postContingencyResults = results.getPostContingencyResults().get(0).getNetworkResult();

        // Contingency has no impact on first component, no result available
        assertNull(postContingencyResults.getBranchResult("l14"));

        // LB3C1 is on the second component, so result are available
        assertNotNull(postContingencyResults.getBranchResult("lc12"));

        NetworkResult operatorStrategyResult = results.getOperatorStrategyResults().get(0).getNetworkResult();

        // Operator strategy with action reconnecting both components is not correctly supported
        // No result on first component and results are available for the second
        assertNull(operatorStrategyResult.getBranchResult("l14"));
        assertNotNull(operatorStrategyResult.getBranchResult("lc12"));
        assertNotNull(operatorStrategyResult.getBranchResult("LB3C1"));
    }

    @Test
    void testSlackBusSelectionExcludeBusWithHighestVoltage() {
        Network network = TwoBusNetworkFactory.createWithAThirdBus();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        OpenLoadFlowParameters.create(securityAnalysisParameters.getLoadFlowParameters())
            .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        // This contingency will cut off bus with the highest voltage level
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new BranchContingency("l23"))));
        assertDoesNotThrow(() -> runSecurityAnalysis(network, contingencies, Collections.emptyList(), securityAnalysisParameters, Collections.emptyList(), Collections.emptyList(), ReportNode.NO_OP));
    }

    @Test
    void testNoMoreVoltageControlledBusOneBusNetwork() {
        Network network = Network.create("test", "code");
        VoltageLevel vl1 = network.newVoltageLevel()
                .setId("VL1")
                .setNominalV(400.)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("B1")
                .add();
        vl1.newGenerator()
                .setId("G1")
                .setMinP(0.)
                .setMaxP(100.)
                .setConnectableBus("B1")
                .setBus("B1")
                .setTargetP(10.)
                .setTargetV(400.)
                .setVoltageRegulatorOn(true)
                .add();

        List<Contingency> contingencies = List.of(new Contingency("G1", new GeneratorContingency("G1")));

        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, parameters);

        // no more voltage controlled bus in network after contingency
        assertSame(PostContingencyComputationStatus.SOLVER_FAILED, result.getPostContingencyResults().get(0).getStatus());
    }

    @Test
    void testNoMoreVoltageControlledBusTwoBusNetwork() {
        Network network = Network.create("test", "code");
        VoltageLevel vl1 = network.newVoltageLevel()
                .setId("VL1")
                .setNominalV(400.)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl1.getBusBreakerView().newBus()
                .setId("B1")
                .add();
        vl1.newGenerator()
                .setId("G1")
                .setMinP(0.)
                .setMaxP(100.)
                .setConnectableBus("B1")
                .setBus("B1")
                .setTargetP(10.)
                .setTargetV(400.)
                .setVoltageRegulatorOn(true)
                .add();

        VoltageLevel vl2 = network.newVoltageLevel()
                .setId("VL2")
                .setNominalV(400.)
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        vl2.getBusBreakerView().newBus()
                .setId("B2")
                .add();
        vl2.newGenerator()
                .setId("G2")
                .setMinP(0.)
                .setMaxP(100.)
                .setConnectableBus("B2")
                .setBus("B2")
                .setTargetP(10.)
                .setTargetV(400.01)
                .setVoltageRegulatorOn(true)
                .add()
                .newMinMaxReactiveLimits()
                .setMinQ(-300)
                .setMaxQ(300)
                .add();

        network.newLine()
                .setId("l12")
                .setBus1("B1")
                .setConnectableBus1("B1")
                .setBus2("B2")
                .setConnectableBus2("B2")
                .setR(0.01)
                .setX(0.01)
                .add();

        LoadFlowParameters parameters = new LoadFlowParameters();
        LoadFlowResult result = runLoadFlow(network, parameters);
        assertTrue(result.isFullyConverged());
        // G2 is blocked at MinQ limit
        assertReactivePowerEquals(-300, network.getGenerator("G2").getTerminal());

        List<Contingency> contingencies = List.of(new Contingency("G1", new GeneratorContingency("G1")));
        SecurityAnalysisResult asResult = runSecurityAnalysis(network, contingencies, parameters);

        // FIXME: This should  converge like the N case with contingency applied - see further below
        assertEquals(PostContingencyComputationStatus.SOLVER_FAILED, asResult.getPostContingencyResults().get(0).getStatus());

        network.getGenerator("G1").disconnect();
        result = runLoadFlow(network, parameters);
        assertTrue(result.isFullyConverged());
        // G2 within reactive limits
        assertReactivePowerEquals(0.0, network.getGenerator("G2").getTerminal());
        assertVoltageEquals(400.01, network.getBusBreakerView().getBus("B2"));
    }

    @Test
    void testComponentSelectionOneCCtwoSC() {
        // Network has one CC with two SC
        Network network = HvdcNetworkFactory.createVsc();
        LfNetworkList networks = new LfNetworkList(Networks.load(network, new LfNetworkParameters().setComputeMainConnectedComponentOnly(false)));
        assertEquals(2, networks.getList().size());

        assertEquals(0, networks.getList().get(0).getNumCC());
        assertEquals(0, networks.getList().get(0).getNumSC());
        assertEquals(0, networks.getList().get(1).getNumCC());
        assertEquals(1, networks.getList().get(1).getNumSC());

        // Main connected component mode and all connected component mode should yield same result
        List<LfNetwork> componentMain = AbstractSecurityAnalysis.getNetworksToSimulate(networks, LoadFlowParameters.ConnectedComponentMode.MAIN);
        assertEquals(2, componentMain.size());
        assertEquals(0, componentMain.get(0).getNumCC());
        assertEquals(0, componentMain.get(0).getNumSC());
        assertEquals(0, componentMain.get(1).getNumCC());
        assertEquals(1, componentMain.get(1).getNumSC());

        List<LfNetwork> componentAll = AbstractSecurityAnalysis.getNetworksToSimulate(networks, LoadFlowParameters.ConnectedComponentMode.ALL);
        assertEquals(2, componentAll.size());
        assertEquals(0, componentAll.get(0).getNumCC());
        assertEquals(0, componentAll.get(0).getNumSC());
        assertEquals(0, componentAll.get(1).getNumCC());
        assertEquals(1, componentAll.get(1).getNumSC());
    }

    @Test
    void testComponentSelectionTwoCC() {
        Network network = FourBusNetworkFactory.createWithTwoScs();
        LfNetworkList networks = new LfNetworkList(Networks.load(network, new LfNetworkParameters().setComputeMainConnectedComponentOnly(false)));
        assertEquals(2, networks.getList().size());

        assertEquals(0, networks.getList().get(0).getNumCC());
        assertEquals(0, networks.getList().get(0).getNumSC());
        assertEquals(1, networks.getList().get(1).getNumCC());
        assertEquals(1, networks.getList().get(1).getNumSC());

        // Main connected component mode should only select component associated to main CC
        List<LfNetwork> componentMain = AbstractSecurityAnalysis.getNetworksToSimulate(networks, LoadFlowParameters.ConnectedComponentMode.MAIN);
        assertEquals(1, componentMain.size());
        assertEquals(0, componentMain.get(0).getNumCC());
        assertEquals(0, componentMain.get(0).getNumSC());

        // All connected component mode should select all component
        List<LfNetwork> componentAll = AbstractSecurityAnalysis.getNetworksToSimulate(networks, LoadFlowParameters.ConnectedComponentMode.ALL);
        assertEquals(2, componentAll.size());
        assertEquals(0, componentAll.get(0).getNumCC());
        assertEquals(0, componentAll.get(0).getNumSC());
        assertEquals(1, componentAll.get(1).getNumCC());
        assertEquals(1, componentAll.get(1).getNumSC());
    }

    @Test
    void testNoCc0Sc0() {
        Network network = ConnectedComponentNetworkFactory.createNoCc0Sc0();
        var compByBus = network.getBusBreakerView().getBusStream().collect(Collectors.toMap(Identifiable::getId, b -> String.format("CC%d SC%d", b.getConnectedComponent().getNum(), b.getSynchronousComponent().getNum())));
        assertEquals(6, compByBus.size());
        assertEquals("CC0 SC1", compByBus.get("b01"));
        assertEquals("CC0 SC2", compByBus.get("b02"));
        assertEquals("CC0 SC3", compByBus.get("b03"));
        assertEquals("CC0 SC4", compByBus.get("b04"));
        assertEquals("CC1 SC0", compByBus.get("b11"));
        assertEquals("CC1 SC0", compByBus.get("b12"));
        LoadFlowParameters lfParametersAll = new LoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.ALL);
        LoadFlowParameters lfParametersMain = new LoadFlowParameters().setConnectedComponentMode(LoadFlowParameters.ConnectedComponentMode.MAIN);
        var lfResultAll = LoadFlow.run(network, lfParametersAll);
        assertTrue(lfResultAll.isFullyConverged());
        var lfResultMain = LoadFlow.run(network, lfParametersMain);
        assertTrue(lfResultAll.isFullyConverged());
        assertEquals(5, lfResultAll.getComponentResults().size()); // 5 SCs
        assertTrue(lfResultMain.isFullyConverged());
        assertEquals(4, lfResultMain.getComponentResults().size()); // 4 SCs

        var saResultMain = runSecurityAnalysis(network, Collections.emptyList(), createNetworkMonitors(network), lfParametersMain);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, saResultMain.getPreContingencyResult().getStatus());
        assertEquals(4, saResultMain.getPreContingencyResult().getNetworkResult().getBusResults().size()); // 4 buses in CC0
        var saResultAll = runSecurityAnalysis(network, Collections.emptyList(), createNetworkMonitors(network), lfParametersAll);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, saResultAll.getPreContingencyResult().getStatus());
        assertEquals(6, saResultAll.getPreContingencyResult().getNetworkResult().getBusResults().size()); // 6 buses in total
    }
}
