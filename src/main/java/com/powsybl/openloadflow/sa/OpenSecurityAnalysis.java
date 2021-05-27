/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.nr.NewtonRaphsonStatus;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.outerloop.AcloadFlowEngine;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivity;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.BranchState;
import com.powsybl.openloadflow.util.BusState;
import com.powsybl.openloadflow.util.LfContingency;
import com.powsybl.openloadflow.util.PropagatedContingency;
import com.powsybl.security.*;
import com.powsybl.security.detectors.DefaultLimitViolationDetector;
import com.powsybl.security.interceptors.SecurityAnalysisInterceptor;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.BusResults;
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.results.ThreeWindingsTransformerResult;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class OpenSecurityAnalysis {

    protected static final Logger LOGGER = LoggerFactory.getLogger(OpenSecurityAnalysis.class);

    protected final Network network;

    protected final LimitViolationDetector detector;

    protected final LimitViolationFilter filter;

    protected final List<SecurityAnalysisInterceptor> interceptors = new ArrayList<>();

    protected final MatrixFactory matrixFactory;

    protected final Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider;

    private final StateMonitorIndex monitorIndex;

    private static final double POST_CONTINGENCY_INCREASING_FACTOR = 1.1;

    public OpenSecurityAnalysis(Network network) {
        this(network, new DefaultLimitViolationDetector(), new LimitViolationFilter());
    }

    public OpenSecurityAnalysis(Network network, LimitViolationDetector detector, LimitViolationFilter filter) {
        this(network, detector, filter, new SparseMatrixFactory(), EvenShiloachGraphDecrementalConnectivity::new);
    }

    public OpenSecurityAnalysis(Network network, LimitViolationDetector detector, LimitViolationFilter filter,
                                MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider) {
        this(network, detector, filter, matrixFactory, connectivityProvider, Collections.emptyList());
    }

    public OpenSecurityAnalysis(Network network, LimitViolationDetector detector, LimitViolationFilter filter,
                                MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider, List<StateMonitor> stateMonitors) {
        this.network = Objects.requireNonNull(network);
        this.detector = Objects.requireNonNull(detector);
        this.filter = Objects.requireNonNull(filter);
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityProvider = Objects.requireNonNull(connectivityProvider);
        this.monitorIndex = new StateMonitorIndex(stateMonitors);
    }

    public void addInterceptor(SecurityAnalysisInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor));
    }

    public boolean removeInterceptor(SecurityAnalysisInterceptor interceptor) {
        return interceptors.remove(Objects.requireNonNull(interceptor));
    }

    public CompletableFuture<SecurityAnalysisReport> run(String workingVariantId, SecurityAnalysisParameters securityAnalysisParameters,
                                                         ContingenciesProvider contingenciesProvider) {
        Objects.requireNonNull(workingVariantId);
        Objects.requireNonNull(securityAnalysisParameters);
        Objects.requireNonNull(contingenciesProvider);
        return CompletableFuture.supplyAsync(() -> {
            String oldWorkingVariantId = network.getVariantManager().getWorkingVariantId();
            network.getVariantManager().setWorkingVariant(workingVariantId);
            SecurityAnalysisReport result = runSync(securityAnalysisParameters, contingenciesProvider);
            network.getVariantManager().setWorkingVariant(oldWorkingVariantId);
            return result;
        });
    }

    abstract SecurityAnalysisReport runSync(SecurityAnalysisParameters securityAnalysisParameters, ContingenciesProvider contingenciesProvider);
    /**
     * Detect violations on branches and on buses
     * @param branches branches on which the violation limits are checked
     * @param buses buses on which the violation limits are checked
     * @param violations list on which the violation limits encountered are added
     */
    protected void detectViolations(Stream<LfBranch> branches, Stream<LfBus> buses, Map<Pair<String, Branch.Side>, LimitViolation> violations) {
        // Detect violation limits on branches
        branches.forEach(branch -> detectBranchViolations(branch, violations));

        // Detect violation limits on buses
        buses.forEach(bus -> detectBusViolations(bus, violations));
    }

    /**
     * Detect violation limits on one branch and add them to the given list
     * @param branch branch of interest
     * @param violations list on which the violation limits encountered are added
     */
    protected void detectBranchViolations(LfBranch branch, Map<Pair<String, Branch.Side>, LimitViolation> violations) {
        // detect violation limits on a branch
        if (branch.getBus1() != null) {
            branch.getLimits1().stream()
                .filter(temporaryLimit1 -> branch.getI1().eval() > temporaryLimit1.getValue())
                .findFirst() // only the most serious violation is added (the limits are sorted in descending gravity)
                .map(temporaryLimit1 -> createLimitViolation1(branch, temporaryLimit1))
                .ifPresent(limitViolation -> violations.put(getSubjectSideId(limitViolation), limitViolation));
        }
        if (branch.getBus2() != null) {
            branch.getLimits2().stream()
                .filter(temporaryLimit2 -> branch.getI2().eval() > temporaryLimit2.getValue())
                .findFirst() // only the most serious violation is added (the limits are sorted in descending gravity)
                .map(temporaryLimit2 -> createLimitViolation2(branch, temporaryLimit2))
                .ifPresent(limitViolation -> violations.put(getSubjectSideId(limitViolation), limitViolation));
        }
    }

    protected static LimitViolation createLimitViolation1(LfBranch branch, AbstractLfBranch.LfLimit temporaryLimit1) {
        double scale1 = PerUnit.SB / branch.getBus1().getNominalV();
        return new LimitViolation(branch.getId(), LimitViolationType.CURRENT, null,
            temporaryLimit1.getAcceptableDuration(), temporaryLimit1.getValue() * scale1,
            (float) 1., branch.getI1().eval() * scale1, Branch.Side.ONE);
    }

    protected static LimitViolation createLimitViolation2(LfBranch branch, AbstractLfBranch.LfLimit temporaryLimit2) {
        double scale2 = PerUnit.SB / branch.getBus2().getNominalV();
        return new LimitViolation(branch.getId(), LimitViolationType.CURRENT, null,
            temporaryLimit2.getAcceptableDuration(), temporaryLimit2.getValue() * scale2,
            (float) 1., branch.getI2().eval() * scale2, Branch.Side.TWO);
    }

    protected static Pair<String, Branch.Side> getSubjectSideId(LimitViolation limitViolation) {
        return Pair.of(limitViolation.getSubjectId(), limitViolation.getSide());
    }

    /**
     * Detect violation limits on one branch and add them to the given list
     * @param bus branch of interest
     * @param violations list on which the violation limits encountered are added
     */
    protected void detectBusViolations(LfBus bus, Map<Pair<String, Branch.Side>, LimitViolation> violations) {
        // detect violation limits on a bus
        double scale = bus.getNominalV();
        Double busV = bus.getV().eval();
        if (!Double.isNaN(bus.getHighVoltageLimit()) && busV > bus.getHighVoltageLimit()) {
            LimitViolation limitViolation1 = new LimitViolation(bus.getVoltageLevelId(), LimitViolationType.HIGH_VOLTAGE, bus.getHighVoltageLimit() * scale,
                    (float) 1., busV * scale);
            violations.put(getSubjectSideId(limitViolation1), limitViolation1);
        }
        if (!Double.isNaN(bus.getLowVoltageLimit()) && busV < bus.getLowVoltageLimit()) {
            LimitViolation limitViolation2 = new LimitViolation(bus.getVoltageLevelId(), LimitViolationType.LOW_VOLTAGE, bus.getHighVoltageLimit() * scale,
                    (float) 1., busV * scale);
            violations.put(getSubjectSideId(limitViolation2), limitViolation2);
        }
    }

    public static void distributedMismatch(LfNetwork network, double mismatch, LoadFlowParameters loadFlowParameters,
                                           OpenLoadFlowParameters openLoadFlowParameters) {
        if (loadFlowParameters.isDistributedSlack() && Math.abs(mismatch) > 0) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(loadFlowParameters.getBalanceType(), openLoadFlowParameters.isLoadPowerFactorConstant());
            activePowerDistribution.run(network, mismatch);
        }
    }

    /**
     * Compares two limit violations
     * @param violation1 first limit violation
     * @param violation2 second limit violation
     * @return true if violation2 is weaker than or equivalent to violation1, otherwise false
     */
    protected static boolean violationWeakenedOrEquivalent(LimitViolation violation1, LimitViolation violation2) {
        if (violation2 != null) {
            if (violation2.getLimit() < violation1.getLimit()) {
                return true; // the limit violated is smaller hence the violation is weaker
            }
            if (violation2.getLimit() == violation1.getLimit()) {
                // the limit violated is the same: we consider the violations equivalent if the new value is close to previous one
                return violation2.getValue() <= violation1.getValue() * POST_CONTINGENCY_INCREASING_FACTOR;
            }
        }
        return false;
    }

    List<LfContingency> createContingencies(List<PropagatedContingency> propagatedContingencies, LfNetwork network) {
        return LfContingency.createContingencies(propagatedContingencies, network, network.createDecrementalConnectivity(connectivityProvider), true);
    }

    private void addMonitorInfo(LfNetwork network, StateMonitor monitor, Collection<BranchResult> branchResultConsumer,
                                Collection<BusResults> busResultsConsumer, Collection<ThreeWindingsTransformerResult> threeWindingsTransformerResultConsumer) {
        network.getBranches().stream().filter(lfBranch -> monitor.getBranchIds().contains(lfBranch.getId()))
                .filter(lfBranch -> !lfBranch.isDisabled())
                .forEach(lfBranch -> branchResultConsumer.add(lfBranch.createBranchResult()));
        network.getBuses().stream().filter(lfBus -> monitor.getVoltageLevelIds().contains(lfBus.getVoltageLevelId()))
                .filter(lfBus -> !lfBus.isDisabled())
                .forEach(lfBus -> busResultsConsumer.add(lfBus.createBusResult()));
        monitor.getThreeWindingsTransformerIds().stream().filter(id -> network.getBusById(id + "_BUS0") != null && !network.getBusById(id + "_BUS0").isDisabled())
                .forEach(id -> threeWindingsTransformerResultConsumer.add(createThreeWindingsTransformerResult(id, network)));
    }

    private ThreeWindingsTransformerResult createThreeWindingsTransformerResult(String threeWindingsTransformerId, LfNetwork network) {
        LfBranch leg1 = network.getBranchById(threeWindingsTransformerId + "_leg_1");
        LfBranch leg2 = network.getBranchById(threeWindingsTransformerId + "_leg_2");
        LfBranch leg3 = network.getBranchById(threeWindingsTransformerId + "_leg_3");
        return new ThreeWindingsTransformerResult(threeWindingsTransformerId, leg1.getP1().eval(), leg1.getQ1().eval(), leg1.getI1().eval(),
                leg2.getP1().eval(), leg2.getQ1().eval(), leg2.getI1().eval(),
                leg3.getP1().eval(), leg3.getQ1().eval(), leg3.getI1().eval());
    }
}
