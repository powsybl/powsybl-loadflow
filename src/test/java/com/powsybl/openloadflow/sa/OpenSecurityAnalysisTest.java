/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.google.common.io.ByteStreams;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.commons.reporter.ReporterModel;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.*;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.LoadDetailAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.OlfBranchResult;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.security.*;
import com.powsybl.security.detectors.DefaultLimitViolationDetector;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.commons.TestUtil.normalizeLineSeparator;
import static java.lang.Double.NaN;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class OpenSecurityAnalysisTest {

    private ComputationManager computationManager;

    private OpenSecurityAnalysisProvider securityAnalysisProvider;

    private OpenLoadFlowProvider loadFlowProvider;

    @BeforeEach
    void setUp() {
        computationManager = Mockito.mock(ComputationManager.class);
        Mockito.when(computationManager.getExecutor()).thenReturn(ForkJoinPool.commonPool());
        MatrixFactory matrixFactory = new DenseMatrixFactory();
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new EvenShiloachGraphDecrementalConnectivityFactory<>();
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);
        loadFlowProvider = new OpenLoadFlowProvider(matrixFactory, connectivityFactory);
    }

    private static Network createNodeBreakerNetwork() {
        Network network = NodeBreakerNetworkFactory.create();

        network.getLine("L1").newCurrentLimits1()
                .setPermanentLimit(940.0)
                .beginTemporaryLimit()
                .setName("60")
                .setAcceptableDuration(60)
                .setValue(1000)
                .endTemporaryLimit()
                .add();
        network.getLine("L1").newCurrentLimits2()
                .setPermanentLimit(940.0)
                .add();
        network.getLine("L2").newCurrentLimits1()
                .setPermanentLimit(940.0)
                .beginTemporaryLimit()
                .setName("60")
                .setAcceptableDuration(60)
                .setValue(950)
                .endTemporaryLimit()
                .add();
        network.getLine("L2").newCurrentLimits2()
                .setPermanentLimit(940.0)
                .beginTemporaryLimit()
                .setName("600")
                .setAcceptableDuration(600)
                .setValue(945)
                .endTemporaryLimit()
                .beginTemporaryLimit()
                .setName("60")
                .setAcceptableDuration(60)
                .setValue(970)
                .endTemporaryLimit()
                .add();

        return network;
    }

    private LoadFlowResult runLoadFlow(Network network, LoadFlowParameters parameters) {
        return loadFlowProvider.run(network, computationManager, network.getVariantManager().getWorkingVariantId(), parameters)
                .join();
    }

    /**
     * Runs a security analysis with default parameters + most meshed slack bus selection
     */
    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies, List<StateMonitor> monitors,
                                                       LoadFlowParameters lfParameters) {
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        return runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies, List<StateMonitor> monitors,
                                                       SecurityAnalysisParameters saParameters) {
        return runSecurityAnalysis(network, contingencies, monitors, saParameters, Reporter.NO_OP);
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies, List<StateMonitor> monitors,
                                                       SecurityAnalysisParameters saParameters, Reporter reporter) {
        ContingenciesProvider provider = n -> contingencies;
        SecurityAnalysisReport report = securityAnalysisProvider.run(network,
                network.getVariantManager().getWorkingVariantId(),
                new DefaultLimitViolationDetector(),
                new LimitViolationFilter(),
                computationManager,
                saParameters,
                provider,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                monitors,
                reporter)
                .join();
        return report.getResult();
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies, List<StateMonitor> monitors) {
        return runSecurityAnalysis(network, contingencies, monitors, new LoadFlowParameters());
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies, LoadFlowParameters loadFlowParameters) {
        return runSecurityAnalysis(network, contingencies, Collections.emptyList(), loadFlowParameters);
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network, List<Contingency> contingencies) {
        return runSecurityAnalysis(network, contingencies, Collections.emptyList());
    }

    private SecurityAnalysisResult runSecurityAnalysis(Network network) {
        return runSecurityAnalysis(network, Collections.emptyList(), Collections.emptyList());
    }

    private static List<StateMonitor> createAllBranchesMonitors(Network network) {
        Set<String> allBranchIds = network.getBranchStream().map(Identifiable::getId).collect(Collectors.toSet());
        return List.of(new StateMonitor(ContingencyContext.all(), allBranchIds, Collections.emptySet(), Collections.emptySet()));
    }

    private static List<StateMonitor> createNetworkMonitors(Network network) {
        Set<String> allBranchIds = network.getBranchStream().map(Identifiable::getId).collect(Collectors.toSet());
        Set<String> allVoltageLevelIds = network.getVoltageLevelStream().map(Identifiable::getId).collect(Collectors.toSet());
        return List.of(new StateMonitor(ContingencyContext.all(), allBranchIds, allVoltageLevelIds, Collections.emptySet()));
    }

    private static List<Contingency> createAllBranchesContingencies(Network network) {
        return network.getBranchStream()
                .map(b -> new Contingency(b.getId(), new BranchContingency(b.getId())))
                .collect(Collectors.toList());
    }

    private static void setSlackBusId(LoadFlowParameters lfParameters, String slackBusId) {
        OpenLoadFlowParameters.create(lfParameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                .setSlackBusId(slackBusId);
    }

    private static Optional<PostContingencyResult> getOptionalPostContingencyResult(SecurityAnalysisResult result, String contingencyId) {
        return result.getPostContingencyResults().stream()
                .filter(r -> r.getContingency().getId().equals(contingencyId))
                .findFirst();
    }

    private static PostContingencyResult getPostContingencyResult(SecurityAnalysisResult result, String contingencyId) {
        return getOptionalPostContingencyResult(result, contingencyId)
                .orElseThrow();
    }

    private static void assertAlmostEquals(BusResult expected, BusResult actual, double epsilon) {
        assertEquals(expected.getVoltageLevelId(), actual.getVoltageLevelId());
        assertEquals(expected.getBusId(), actual.getBusId());
        assertEquals(expected.getV(), actual.getV(), epsilon);
        assertEquals(expected.getAngle(), actual.getAngle(), epsilon);
    }

    private static void assertAlmostEquals(BranchResult expected, BranchResult actual, double epsilon) {
        assertEquals(expected.getBranchId(), actual.getBranchId());
        assertEquals(expected.getP1(), actual.getP1(), epsilon);
        assertEquals(expected.getQ1(), actual.getQ1(), epsilon);
        assertEquals(expected.getI1(), actual.getI1(), epsilon);
        assertEquals(expected.getP2(), actual.getP2(), epsilon);
        assertEquals(expected.getQ2(), actual.getQ2(), epsilon);
        assertEquals(expected.getI2(), actual.getI2(), epsilon);
    }

    private static void assertAlmostEquals(ThreeWindingsTransformerResult expected, ThreeWindingsTransformerResult actual, double epsilon) {
        assertEquals(expected.getThreeWindingsTransformerId(), actual.getThreeWindingsTransformerId());
        assertEquals(expected.getP1(), actual.getP1(), epsilon);
        assertEquals(expected.getQ1(), actual.getQ1(), epsilon);
        assertEquals(expected.getI1(), actual.getI1(), epsilon);
        assertEquals(expected.getP2(), actual.getP2(), epsilon);
        assertEquals(expected.getQ2(), actual.getQ2(), epsilon);
        assertEquals(expected.getI2(), actual.getI2(), epsilon);
        assertEquals(expected.getP3(), actual.getP3(), epsilon);
        assertEquals(expected.getQ3(), actual.getQ3(), epsilon);
        assertEquals(expected.getI3(), actual.getI3(), epsilon);
    }

    @Test
    void testCurrentLimitViolations() {
        Network network = createNodeBreakerNetwork();

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        setSlackBusId(lfParameters, "VL1_1");

        List<Contingency> contingencies = Stream.of("L1", "L2")
            .map(id -> new Contingency(id, new BranchContingency(id)))
            .collect(Collectors.toList());

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertTrue(result.getPostContingencyResults().get(1).getLimitViolationsResult().isComputationOk());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
    }

    @Test
    void testCurrentLimitViolations2() {
        Network network = createNodeBreakerNetwork();
        network.getLine("L1").getCurrentLimits1().ifPresent(limits -> limits.setPermanentLimit(200));

        List<Contingency> contingencies = List.of(new Contingency("L2", new BranchContingency("L2")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(1, result.getPostContingencyResults().size());
        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());
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

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(0, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());
        assertEquals(3, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());

        List<LimitViolation> limitViolations = result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations();
        Optional<LimitViolation> limitViolationL21 = limitViolations.stream().filter(limitViolation -> limitViolation.getSubjectId().equals("L2") && limitViolation.getSide() == Branch.Side.ONE).findFirst();
        assertTrue(limitViolationL21.isPresent());
        assertEquals(0, limitViolationL21.get().getAcceptableDuration());
        assertEquals(950, limitViolationL21.get().getLimit());
        Optional<LimitViolation> limitViolationL22 = limitViolations.stream().filter(limitViolation -> limitViolation.getSubjectId().equals("L2") && limitViolation.getSide() == Branch.Side.TWO).findFirst();
        assertTrue(limitViolationL22.isPresent());
        assertEquals(0, limitViolationL22.get().getAcceptableDuration());
        assertEquals(970, limitViolationL22.get().getLimit());

        assertTrue(result.getPostContingencyResults().get(1).getLimitViolationsResult().isComputationOk());
        assertEquals(3, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());

        List<LimitViolation> limitViolations1 = result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations();
        LimitViolation lowViolation = limitViolations1.get(2);
        assertEquals(LimitViolationType.LOW_VOLTAGE, lowViolation.getLimitType());
        assertEquals(370, lowViolation.getLimit());
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

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(1, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().size());
        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());

        assertEquals(0, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertTrue(result.getPostContingencyResults().get(1).getLimitViolationsResult().isComputationOk());
        assertEquals(0, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
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

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());

        LoadFlowParameters loadFlowParameters = new LoadFlowParameters()
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        result = runSecurityAnalysis(network, contingencies, loadFlowParameters);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testNoGenerator() {
        Network network = EurostagTutorialExample1Factory.create();
        network.getGenerator("GEN").getTerminal().disconnect();

        SecurityAnalysisResult result = runSecurityAnalysis(network);
        assertFalse(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testNoRemainingGenerator() {
        Network network = EurostagTutorialExample1Factory.create();

        List<Contingency> contingencies = List.of(new Contingency("NGEN_NHV1", new BranchContingency("NGEN_NHV1")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testNoRemainingLoad() {
        Network network = EurostagTutorialExample1Factory.create();

        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDistributedSlack(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("NHV2_NLOAD", new BranchContingency("NHV2_NLOAD")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, lfParameters);

        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testSaWithSeveralConnectedComponents() {
        Network network = ConnectedComponentNetworkFactory.createTwoUnconnectedCC();

        // Testing all contingencies at once
        List<Contingency> contingencies = createAllBranchesContingencies(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
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
        List<BusResult> busResults = preContingencyResult.getNeworkResult().getBusResults();
        BusResult expectedBus = new BusResult("VLLOAD", "NLOAD", 147.6, -9.6);
        assertEquals(1, busResults.size());
        assertAlmostEquals(expectedBus, busResults.get(0), 0.1);

        assertEquals(2, preContingencyResult.getNeworkResult().getBranchResults().size());
        assertAlmostEquals(new BranchResult("NHV1_NHV2_1", 302, 99, 457, -300, -137.2, 489),
                           preContingencyResult.getNeworkResult().getBranchResult("NHV1_NHV2_1"), 1);
        assertAlmostEquals(new BranchResult("NGEN_NHV1", 606, 225, 15226, -605, -198, 914),
                           preContingencyResult.getNeworkResult().getBranchResult("NGEN_NHV1"), 1);

        //No result when the branch itself is disconnected
        assertNull(result.getPostContingencyResults().get(0).getNetworkResult().getBranchResult("NHV1_NHV2_1"));

        assertAlmostEquals(new BranchResult("NHV1_NHV2_1", 611, 334, 1009, -601, -285, 1048),
                result.getPostContingencyResults().get(1).getNetworkResult().getBranchResult("NHV1_NHV2_1"), 1);
        assertAlmostEquals(new BranchResult("NGEN_NHV1", 611, 368, 16815, -611, -334, 1009),
                           result.getPostContingencyResults().get(1).getNetworkResult().getBranchResult("NGEN_NHV1"), 1);
    }

    @Test
    void testSaWithStateMonitorNotExistingBranchBus() {
        Network network = DistributedSlackNetworkFactory.create();

        List<StateMonitor> monitors = List.of(
            new StateMonitor(ContingencyContext.all(), Collections.singleton("l1"), Collections.singleton("bus"), Collections.singleton("three windings"))
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(0, result.getPreContingencyResult().getNeworkResult().getBusResults().size());
        assertEquals(0, result.getPreContingencyResult().getNeworkResult().getBranchResults().size());
    }

    @Test
    void testSaWithStateMonitorDisconnectBranch() {
        Network network = DistributedSlackNetworkFactory.create();
        network.getBranch("l24").getTerminal1().disconnect();

        List<StateMonitor> monitors = new ArrayList<>();
        monitors.add(new StateMonitor(ContingencyContext.all(), Collections.singleton("l24"), Collections.singleton("b1_vl"), emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(1, result.getPreContingencyResult().getNeworkResult().getBusResults().size());

        assertEquals(new BusResult("b1_vl", "b1", 400, 0.003581299841270782), result.getPreContingencyResult().getNeworkResult().getBusResults().get(0));
        assertEquals(1, result.getPreContingencyResult().getNeworkResult().getBranchResults().size());
        assertEquals(new BranchResult("l24", NaN, NaN, NaN, 0.0, -0.0, 0.0),
                     result.getPreContingencyResult().getNeworkResult().getBranchResults().get(0));

        network = DistributedSlackNetworkFactory.create();
        network.getBranch("l24").getTerminal2().disconnect();

        result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(0, result.getPreContingencyResult().getNeworkResult().getBranchResults().size());

        network = DistributedSlackNetworkFactory.create();
        network.getBranch("l24").getTerminal2().disconnect();
        network.getBranch("l24").getTerminal1().disconnect();

        result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(0, result.getPreContingencyResult().getNeworkResult().getBranchResults().size());
    }

    @Test
    void testSaWithStateMonitorDanglingLine() {
        Network network = BoundaryFactory.create();

        List<StateMonitor> monitors = new ArrayList<>();
        monitors.add(new StateMonitor(ContingencyContext.all(), Collections.singleton("dl1"), Collections.singleton("vl1"), emptySet()));

        List<Contingency> contingencies = createAllBranchesContingencies(network);
        CompletionException exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors));
        assertEquals("Unsupported type of branch for branch result: dl1", exception.getCause().getMessage());
    }

    @Test
    void testSaWithStateMonitorLfLeg() {
        Network network = T3wtFactory.create();

        // Testing all contingencies at once
        List<StateMonitor> monitors = List.of(
            new StateMonitor(ContingencyContext.all(), emptySet(), emptySet(), Collections.singleton("3wt"))
        );

        SecurityAnalysisResult result = runSecurityAnalysis(network, createAllBranchesContingencies(network), monitors);

        assertEquals(1, result.getPreContingencyResult().getNeworkResult().getThreeWindingsTransformerResults().size());
        assertAlmostEquals(new ThreeWindingsTransformerResult("3wt", 161, 82, 258,
                                                              -161, -74, 435, 0, 0, 0),
                result.getPreContingencyResult().getNeworkResult().getThreeWindingsTransformerResults().get(0), 1);
    }

    @Test
    void testSaDcMode() {
        Network fourBusNetwork = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl_0");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(fourBusNetwork);

        fourBusNetwork.getLine("l14").newActivePowerLimits1().setPermanentLimit(0.1).add();
        fourBusNetwork.getLine("l12").newActivePowerLimits1().setPermanentLimit(0.2).add();
        fourBusNetwork.getLine("l23").newActivePowerLimits1().setPermanentLimit(0.25).add();
        fourBusNetwork.getLine("l34").newActivePowerLimits1().setPermanentLimit(0.15).add();
        fourBusNetwork.getLine("l13").newActivePowerLimits1().setPermanentLimit(0.1).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(fourBusNetwork, contingencies, monitors, securityAnalysisParameters);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(2, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(2, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(2).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(3).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(4).getLimitViolationsResult().getLimitViolations().size());

        //Branch result for first contingency
        assertEquals(5, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().size());

        //Check branch results for flowTransfer computation for contingency on l14
        PostContingencyResult postContl14 = getPostContingencyResult(result, "l14");
        assertEquals("l14", postContl14.getContingency().getId());

        BranchResult brl14l12 = postContl14.getNetworkResult().getBranchResult("l12");
        assertEquals(0.333, brl14l12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l12.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l14 = postContl14.getNetworkResult().getBranchResult("l14");
        assertEquals(NaN, brl14l14.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(NaN, brl14l14.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

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
    void testSaDcModeWithIncreasedParameters() {
        Network fourBusNetwork = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl_0");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);
        SecurityAnalysisParameters.IncreasedViolationsParameters increasedViolationsParameters = new SecurityAnalysisParameters.IncreasedViolationsParameters();
        increasedViolationsParameters.setFlowProportionalThreshold(0);
        securityAnalysisParameters.setIncreasedViolationsParameters(increasedViolationsParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(fourBusNetwork);

        fourBusNetwork.getLine("l14").newActivePowerLimits1().setPermanentLimit(0.1).add();
        fourBusNetwork.getLine("l12").newActivePowerLimits1().setPermanentLimit(0.2).add();
        fourBusNetwork.getLine("l23").newActivePowerLimits1().setPermanentLimit(0.25).add();
        fourBusNetwork.getLine("l34").newActivePowerLimits1().setPermanentLimit(0.15).add();
        fourBusNetwork.getLine("l13").newActivePowerLimits1().setPermanentLimit(0.1).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(fourBusNetwork, contingencies, monitors, securityAnalysisParameters);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertEquals(5, result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations().size());
        assertEquals(5, result.getPostContingencyResults().size());
        assertEquals(3, result.getPostContingencyResults().get(0).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(3, result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(2).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(3).getLimitViolationsResult().getLimitViolations().size());
        assertEquals(4, result.getPostContingencyResults().get(4).getLimitViolationsResult().getLimitViolations().size());

        //Branch result for first contingency
        assertEquals(5, result.getPostContingencyResults().get(0).getNetworkResult().getBranchResults().size());

        //Check branch results for flowTransfer computation for contingency on l14
        PostContingencyResult postContl14 = getPostContingencyResult(result, "l14");
        assertEquals("l14", postContl14.getContingency().getId());

        BranchResult brl14l12 = postContl14.getNetworkResult().getBranchResult("l12");
        assertEquals(0.333, brl14l12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, brl14l12.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

        BranchResult brl14l14 = postContl14.getNetworkResult().getBranchResult("l14");
        assertEquals(NaN, brl14l14.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(NaN, brl14l14.getFlowTransfer(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(-66.667, preContingencyResult.getNeworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-66.667, preContingencyResult.getNeworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-66.667, preContingencyResult.getNeworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(-0.659, preContingencyResult.getNeworkResult().getBranchResult("T2wT2").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.659, preContingencyResult.getNeworkResult().getBranchResult("T2wT").getQ1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.05, preContingencyResult.getNeworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getR1(), 0d);
        assertEquals(1.050302, preContingencyResult.getNeworkResult().getBranchResult("T2wT2").getExtension(OlfBranchResult.class).getContinuousR1(), LoadFlowAssert.DELTA_RHO);
        assertEquals(1.05, preContingencyResult.getNeworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getR1(), 0d);
        assertEquals(1.050302, preContingencyResult.getNeworkResult().getBranchResult("T2wT").getExtension(OlfBranchResult.class).getContinuousR1(), LoadFlowAssert.DELTA_RHO);

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
        assertEquals(-6.181, preContingencyResult.getNeworkResult().getBranchResult("LINE_12").getQ2(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(-108.596, preContingencyResult.getNeworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(54.298, preContingencyResult.getNeworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(54.298, preContingencyResult.getNeworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult tr2ContingencyResult = getPostContingencyResult(result, "tr2");
        assertEquals(-107.543, tr2ContingencyResult.getNetworkResult().getBranchResult("tr1").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(107.543, tr2ContingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithPhaseControl() {
        Network network = PhaseControlFactory.createNetworkWithT2wt();

        network.newLine().setId("L3")
                .setVoltageLevel1("VL1")
                .setConnectableBus1("B1")
                .setBus1("B1")
                .setVoltageLevel2("VL2")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .setG1(0.0)
                .setB1(0.0)
                .setG2(0.0)
                .setB2(0.0)
                .add();

        network.newLine().setId("L4")
                .setVoltageLevel1("VL3")
                .setConnectableBus1("B3")
                .setBus1("B3")
                .setVoltageLevel2("VL2")
                .setConnectableBus2("B2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .setG1(0.0)
                .setB1(0.0)
                .setG2(0.0)
                .setB2(0.0)
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
        assertEquals(5.682, preContingencyResult.getNeworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(59.019, preContingencyResult.getNeworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(5.682, preContingencyResult.getNeworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(29.509, preContingencyResult.getNeworkResult().getBranchResult("L4").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(88.634, preContingencyResult.getNeworkResult().getBranchResult("PS1").getP1(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(42.342, preContingencyResult.getNeworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.342, preContingencyResult.getNeworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult = getPostContingencyResult(result, "SHUNT2");
        assertEquals(0.0, contingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(42.914, contingencyResult.getNetworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult tr3ContingencyResult = getPostContingencyResult(result, "tr3");
        assertEquals(42.914, tr3ContingencyResult.getNetworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
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
        assertEquals(82.342, preContingencyResult.getNeworkResult().getBranchResult("tr2").getQ2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(41.495, preContingencyResult.getNeworkResult().getBranchResult("tr3").getQ2(), LoadFlowAssert.DELTA_POWER);

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

    @Test
    void testDcSaWithLoadContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDc(true);
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l2", new LoadContingency("l2")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("l4", new LoadContingency("l4")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(129.411, preContingencyResult.getNeworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(64.706, preContingencyResult.getNeworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-58.823, preContingencyResult.getNeworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

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

    @Test
    void testDcSaWithGeneratorContingency() {
        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getGenerator("g2").setTargetV(400).setVoltageRegulatorOn(true);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        parameters.setDc(true);

        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("g2", new GeneratorContingency("g2")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(110.0, preContingencyResult.getNeworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(40.0, preContingencyResult.getNeworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-50.0, preContingencyResult.getNeworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(129.412, preContingencyResult.getNeworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-64.706, preContingencyResult.getNeworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(58.823, preContingencyResult.getNeworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(122.857, preContingencyResult.getNeworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-69.999, preContingencyResult.getNeworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, preContingencyResult.getNeworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

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
        parameters.setNoGeneratorReactiveLimits(true);

        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")),
                new Contingency("l34", new BranchContingency("l34")),
                new Contingency("g2", new GeneratorContingency("g2")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, parameters);

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(110, preContingencyResult.getNeworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-40, preContingencyResult.getNeworkResult().getBranchResult("l14").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(50.0, preContingencyResult.getNeworkResult().getBranchResult("l34").getP2(), LoadFlowAssert.DELTA_POWER);

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
        assertEquals(22.111, preContingencyResult.getNeworkResult().getBranchResult("LINE_12").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult contingencyResult = getPostContingencyResult(result, "T2wT");
        assertEquals(11.228, contingencyResult.getNetworkResult().getBranchResult("LINE_12").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testPostContingencyFiltering() {
        Network network = EurostagTutorialExample1Factory.createWithFixedCurrentLimits();
        network.getLine("NHV1_NHV2_2").newCurrentLimits1()
                .setPermanentLimit(300)
                .add();
        network.getVoltageLevel("VLHV1").setLowVoltageLimit(410);

        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")));
        SecurityAnalysisParameters parameters = new SecurityAnalysisParameters();
        parameters.getIncreasedViolationsParameters().setFlowProportionalThreshold(0.0);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);

        List<LimitViolation> preContingencyLimitViolationsOnLine = result.getPreContingencyResult().getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSide().equals(Branch.Side.ONE)).collect(Collectors.toList());
        assertEquals(LimitViolationType.CURRENT, preContingencyLimitViolationsOnLine.get(0).getLimitType());
        assertEquals(459, preContingencyLimitViolationsOnLine.get(0).getValue(), LoadFlowAssert.DELTA_I);

        List<LimitViolation> postContingencyLimitViolationsOnLine = result.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSide().equals(Branch.Side.ONE)).collect(Collectors.toList());
        assertEquals(LimitViolationType.CURRENT, postContingencyLimitViolationsOnLine.get(0).getLimitType());
        assertEquals(1014.989, postContingencyLimitViolationsOnLine.get(0).getValue(), LoadFlowAssert.DELTA_I);

        List<LimitViolation> preContingencyLimitViolationsOnVoltageLevel = result.getPreContingencyResult().getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(LimitViolationType.LOW_VOLTAGE, preContingencyLimitViolationsOnVoltageLevel.get(0).getLimitType());
        assertEquals(400.63, preContingencyLimitViolationsOnVoltageLevel.get(0).getValue(), LoadFlowAssert.DELTA_V);

        List<LimitViolation> postContingencyLimitViolationsOnVoltageLevel = result.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(LimitViolationType.LOW_VOLTAGE, postContingencyLimitViolationsOnVoltageLevel.get(0).getLimitType());
        assertEquals(396.70, postContingencyLimitViolationsOnVoltageLevel.get(0).getValue(), LoadFlowAssert.DELTA_V);

        parameters.getIncreasedViolationsParameters().setFlowProportionalThreshold(1.5);
        parameters.getIncreasedViolationsParameters().setLowVoltageProportionalThreshold(0.1);
        parameters.getIncreasedViolationsParameters().setLowVoltageAbsoluteThreshold(5);
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies, Collections.emptyList(), parameters);

        List<LimitViolation> postContingencyLimitViolationsOnLine2 = result2.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("NHV1_NHV2_2") && violation.getSide().equals(Branch.Side.ONE)).collect(Collectors.toList());
        assertEquals(0, postContingencyLimitViolationsOnLine2.size());

        List<LimitViolation> postContingencyLimitViolationsOnVoltageLevel2 = result2.getPostContingencyResults().get(0).getLimitViolationsResult()
                .getLimitViolations().stream().filter(violation -> violation.getSubjectId().equals("VLHV1")).collect(Collectors.toList());
        assertEquals(0, postContingencyLimitViolationsOnVoltageLevel2.size());
    }

    @Test
    void testViolationsWeakenedOrEquivalent() {
        LimitViolation violation1 = new LimitViolation("voltageLevel1", LimitViolationType.HIGH_VOLTAGE, 420, 1, 421);
        LimitViolation violation2 =  new LimitViolation("voltageLevel1", LimitViolationType.HIGH_VOLTAGE, 420, 1, 425.20);
        SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters = new SecurityAnalysisParameters.IncreasedViolationsParameters();
        violationsParameters.setFlowProportionalThreshold(1.5);
        violationsParameters.setHighVoltageProportionalThreshold(0.1);
        violationsParameters.setHighVoltageAbsoluteThreshold(3);
        assertFalse(LimitViolationManager.violationWeakenedOrEquivalent(violation1, violation2, violationsParameters));
        violationsParameters.setHighVoltageProportionalThreshold(0.01); // 4.21 kV
        violationsParameters.setHighVoltageAbsoluteThreshold(5);
        assertTrue(LimitViolationManager.violationWeakenedOrEquivalent(violation1, violation2, violationsParameters));

        LimitViolation violation3 = new LimitViolation("voltageLevel1", LimitViolationType.LOW_VOLTAGE, 380, 1, 375);
        LimitViolation violation4 =  new LimitViolation("voltageLevel1", LimitViolationType.LOW_VOLTAGE, 380, 1, 371.26);
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
        assertTrue(l1ContingencyResult.getLimitViolationsResult().isComputationOk());
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
    void testHvdcAcEmulation() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

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
        assertEquals(-0.883, preContingencyResult.getNeworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult g1ContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(-0.696, g1ContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        List<Contingency> contingencies2 = new ArrayList<>();
        contingencies2.add(Contingency.hvdcLine("hvdc34"));
        contingencies2.add(Contingency.generator("g1"));
        SecurityAnalysisResult result2 = runSecurityAnalysis(network, contingencies2, monitors, parameters);

        // post-contingency tests
        PostContingencyResult hvdcContingencyResult = getPostContingencyResult(result2, "hvdc34");
        assertEquals(-0.99999, hvdcContingencyResult.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult g1ContingencyResult2 = getPostContingencyResult(result, "g1");
        assertEquals(-0.696, g1ContingencyResult2.getNetworkResult().getBranchResult("l25").getP1(), LoadFlowAssert.DELTA_POWER);
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
        assertEquals(1.360, preContingencyResult.getNeworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.360, preContingencyResult.getNeworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.596, preContingencyResult.getNeworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

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
        network.getGeneratorStream().forEach(gen -> gen.setMaxP(2 * gen.getMaxP()));

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
        assertEquals(1.25, preContingencyResult.getNeworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.228, preContingencyResult.getNeworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.727, preContingencyResult.getNeworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

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
        assertFalse(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testDivergenceStatus() {
        Network network = EurostagTutorialExample1Factory.create();
        network.getLine("NHV1_NHV2_1").setR(100).setX(-999);
        network.getLine("NHV1_NHV2_2").setR(100).setX(-999);
        SecurityAnalysisResult result = runSecurityAnalysis(network);
        assertFalse(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
    }

    @Test
    void testSwitchContingency() {
        Network network = createNodeBreakerNetwork();

        List<Contingency> contingencies = List.of(new Contingency("C", new SwitchContingency("C")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
        assertTrue(result.getPostContingencyResults().get(0).getLimitViolationsResult().isComputationOk());

        // pre-contingency tests
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(301.884, preContingencyResult.getNeworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-300, preContingencyResult.getNeworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.884, preContingencyResult.getNeworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-300, preContingencyResult.getNeworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);

        // post-contingency tests
        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "C");
        assertEquals(3.912, postContingencyResult.getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-3.895, postContingencyResult.getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(603.769, postContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-596.104, postContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
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
        assertEquals(-299.977, preContingencyResult.getNeworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.862, preContingencyResult.getNeworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "L1");
        assertEquals(-599.882, postContingencyResult.getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(608.214, postContingencyResult.getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testDcPermanentCurrentLimitViolations() {
        Network network = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        setSlackBusId(lfParameters, "b1_vl_0");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        network.getLine("l14").newCurrentLimits1().setPermanentLimit(60.0).add();
        network.getLine("l12").newCurrentLimits1().setPermanentLimit(120.0).add();
        network.getLine("l23").newCurrentLimits2().setPermanentLimit(150.0).add();
        network.getLine("l34").newCurrentLimits1().setPermanentLimit(90.0).add();
        network.getLine("l13").newCurrentLimits2().setPermanentLimit(60.0).add();

        List<StateMonitor> monitors = List.of(new StateMonitor(ContingencyContext.all(), Set.of("l14", "l12", "l23", "l34", "l13"), Collections.emptySet(), Collections.emptySet()));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
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

    @Test
    void testDcTemporaryCurrentLimitViolations() {
        Network network = FourBusNetworkFactory.create();
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters lfParameters = new LoadFlowParameters()
                .setDc(true);
        OpenLoadFlowParameters lfParametersExt = new OpenLoadFlowParameters().setDcPowerFactor(Math.tan(0.4));
        lfParameters.addExtension(OpenLoadFlowParameters.class, lfParametersExt);
        setSlackBusId(lfParameters, "b1_vl_0");
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

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

        assertTrue(result.getPreContingencyResult().getLimitViolationsResult().isComputationOk());
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
        setSlackBusId(parameters, "BUS_1");
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        List<Contingency> contingencies = List.of(new Contingency("T3wT", new ThreeWindingsTransformerContingency("T3wT")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters);

        network.getThreeWindingsTransformer("T3wT").getLeg1().getTerminal().disconnect();
        network.getThreeWindingsTransformer("T3wT").getLeg2().getTerminal().disconnect();
        network.getThreeWindingsTransformer("T3wT").getLeg3().getTerminal().disconnect();
        setSlackBusId(parameters, "VL_1_0");
        LoadFlow.run(network, parameters);

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
        assertEquals(75.18, result.getPreContingencyResult().getNeworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(3.333, getPostContingencyResult(result, "dl1").getNetworkResult().getBranchResult("l1").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void reportTest() throws IOException {
        var network = EurostagTutorialExample1Factory.create();

        List<Contingency> contingencies = createAllBranchesContingencies(network);

        ReporterModel reporter = new ReporterModel("TestSecurityAnalysis", "Test security analysis report");

        runSecurityAnalysis(network, contingencies, Collections.emptyList(), new SecurityAnalysisParameters(), reporter);

        String refLogExport = normalizeLineSeparator(new String(ByteStreams.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/saReport.txt"))), StandardCharsets.UTF_8));

        StringWriter writer = new StringWriter();
        reporter.export(writer);
        String logExport = normalizeLineSeparator(writer.toString());

        assertEquals(refLogExport, logExport);
    }
}
