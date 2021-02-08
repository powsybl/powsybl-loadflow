/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.google.common.base.Stopwatch;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.nr.NewtonRaphsonStatus;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.outerloop.AcloadFlowEngine;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.PerUnit;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.BusState;
import com.powsybl.openloadflow.util.ContingencyContext;
import com.powsybl.security.*;
import com.powsybl.security.interceptors.SecurityAnalysisInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class OpenSecurityAnalysis implements SecurityAnalysis {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSecurityAnalysis.class);

    private final Network network;

    private final LimitViolationDetector detector;

    private final LimitViolationFilter filter;

    private final List<SecurityAnalysisInterceptor> interceptors = new ArrayList<>();

    private final MatrixFactory matrixFactory;

    private final Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider;

    public OpenSecurityAnalysis(Network network, LimitViolationDetector detector, LimitViolationFilter filter,
                                MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider) {
        this.network = Objects.requireNonNull(network);
        this.detector = Objects.requireNonNull(detector);
        this.filter = Objects.requireNonNull(filter);
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityProvider = Objects.requireNonNull(connectivityProvider);
    }

    @Override
    public void addInterceptor(SecurityAnalysisInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor));
    }

    @Override
    public boolean removeInterceptor(SecurityAnalysisInterceptor interceptor) {
        return interceptors.remove(Objects.requireNonNull(interceptor));
    }

    @Override
    public CompletableFuture<SecurityAnalysisResult> run(String workingVariantId, SecurityAnalysisParameters securityAnalysisParameters, ContingenciesProvider contingenciesProvider) {
        Objects.requireNonNull(workingVariantId);
        Objects.requireNonNull(securityAnalysisParameters);
        Objects.requireNonNull(contingenciesProvider);
        return CompletableFuture.supplyAsync(() -> {
            String oldWorkingVariantId = network.getVariantManager().getWorkingVariantId();
            network.getVariantManager().setWorkingVariant(workingVariantId);
            SecurityAnalysisResult result = runSync(securityAnalysisParameters, contingenciesProvider);
            network.getVariantManager().setWorkingVariant(oldWorkingVariantId);
            return result;
        });
    }

    SecurityAnalysisResult runSync(SecurityAnalysisParameters securityAnalysisParameters, ContingenciesProvider contingenciesProvider) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        LoadFlowParameters lfParameters = securityAnalysisParameters.getLoadFlowParameters();
        OpenLoadFlowParameters lfParametersExt = OpenLoadFlowProvider.getParametersExt(securityAnalysisParameters.getLoadFlowParameters());
        // in some post-contingency computation, it does not remain elements to participate to slack distribution.
        // in that case, the remaining mismatch is put on the slack bus and no exception is thrown.
        lfParametersExt.setThrowsExceptionInCaseOfSlackDistributionFailure(false);

        // load contingencies
        List<Contingency> contingencies = contingenciesProvider.getContingencies(network);

        // try to find all switches impacted by at least one contingency and for each contingency the branches impacted
        Set<Switch> allSwitchesToOpen = new HashSet<>();
        List<ContingencyContext> contingencyContexts = ContingencyContext.getContingencyContexts(network, contingencies, allSwitchesToOpen);

        AcLoadFlowParameters acParameters = OpenLoadFlowProvider.createAcParameters(network, matrixFactory, lfParameters, lfParametersExt, true);

        // create networks including all necessary switches
        List<LfNetwork> lfNetworks = createNetworks(allSwitchesToOpen, acParameters);

        // run simulation on largest network
        LfNetwork largestNetwork = lfNetworks.get(0);
        SecurityAnalysisResult result = runSimulations(largestNetwork, contingencyContexts, acParameters, lfParameters, lfParametersExt);

        stopwatch.stop();
        LOGGER.info("Security analysis done in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));

        return result;
    }

    List<LfNetwork> createNetworks(Set<Switch> allSwitchesToOpen, AcLoadFlowParameters acParameters) {
        List<LfNetwork> lfNetworks;
        String tmpVariantId = "olf-tmp-" + UUID.randomUUID().toString();
        network.getVariantManager().cloneVariant(network.getVariantManager().getWorkingVariantId(), tmpVariantId);
        try {
            network.getSwitchStream().filter(sw -> sw.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER)
                .forEach(sw -> sw.setRetained(false));
            allSwitchesToOpen.forEach(sw -> sw.setRetained(true));
            lfNetworks = AcloadFlowEngine.createNetworks(network, acParameters);
        } finally {
            network.getVariantManager().removeVariant(tmpVariantId);
        }
        return lfNetworks;
    }

    /**
     * Detect violations on branches and on buses
     * @param branches branches on which the violation limits are checked
     * @param buses buses on which the violation limits are checked
     * @param violations list on which the violation limits encountered are added
     */
    private void detectViolations(Stream<LfBranch> branches, Stream<LfBus> buses, List<LimitViolation> violations) {
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
    private void detectBranchViolations(LfBranch branch, List<LimitViolation> violations) {
        // detect violation limits on a branch
        double scale = 1;
        if (branch.getBus1() != null && branch.getI1() > branch.getPermanentLimit1()) {
            scale = PerUnit.SB / branch.getBus1().getNominalV();
            LimitViolation limitViolation1 = new LimitViolation(branch.getId(), LimitViolationType.CURRENT, (String) null,
                    2147483647, branch.getPermanentLimit1() * scale, (float) 1., branch.getI1() * scale, Branch.Side.ONE);
            violations.add(limitViolation1);
        }
        if (branch.getBus2() != null && branch.getI2() > branch.getPermanentLimit2()) {
            scale = PerUnit.SB / branch.getBus2().getNominalV();
            LimitViolation limitViolation2 = new LimitViolation(branch.getId(), LimitViolationType.CURRENT, (String) null,
                    2147483647, branch.getPermanentLimit2() * scale, (float) 1., branch.getI2() * scale, Branch.Side.TWO);
            violations.add(limitViolation2);
        }
        //TODO: temporary limit violation detection
    }

    /**
     * Detect violation limits on one branch and add them to the given list
     * @param bus branch of interest
     * @param violations list on which the violation limits encountered are added
     */
    private void detectBusViolations(LfBus bus, List<LimitViolation> violations) {
        // detect violation limits on a bus
        double scale = bus.getNominalV();
        if (!Double.isNaN(bus.getHighVoltageLimit()) && bus.getV() > bus.getHighVoltageLimit()) {
            LimitViolation limitViolation1 = new LimitViolation(bus.getVoltageLevelId(), LimitViolationType.HIGH_VOLTAGE, bus.getHighVoltageLimit() * scale,
                    (float) 1., bus.getV() * scale);
            violations.add(limitViolation1);
        }
        if (!Double.isNaN(bus.getLowVoltageLimit()) && bus.getV() < bus.getLowVoltageLimit()) {
            LimitViolation limitViolation2 = new LimitViolation(bus.getVoltageLevelId(), LimitViolationType.LOW_VOLTAGE, bus.getHighVoltageLimit() * scale,
                    (float) 1., bus.getV() * scale);
            violations.add(limitViolation2);
        }
    }

    private SecurityAnalysisResult runSimulations(LfNetwork network, List<ContingencyContext> contingencyContexts, AcLoadFlowParameters acParameters,
                                                  LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters openLoadFlowParameters) {
        // create a contingency list that impact the network
        List<LfContingency> contingencies = createContingencies(contingencyContexts, network);

        // run pre-contingency simulation
        AcloadFlowEngine engine = new AcloadFlowEngine(network, acParameters);
        AcLoadFlowResult preContingencyLoadFlowResult = engine.run();
        boolean preContingencyComputationOk = preContingencyLoadFlowResult.getNewtonRaphsonStatus() == NewtonRaphsonStatus.CONVERGED;
        List<LimitViolation> preContingencyLimitViolations = new ArrayList<>();
        LimitViolationsResult preContingencyResult = new LimitViolationsResult(preContingencyComputationOk, preContingencyLimitViolations);

        // only run post-contingency simulations if pre-contingency simulation is ok
        List<PostContingencyResult> postContingencyResults = new ArrayList<>();
        if (preContingencyComputationOk) {
            detectViolations(network.getBranches().stream(), network.getBuses().stream(), preContingencyLimitViolations);

            LOGGER.info("Save pre-contingency state");

            // save base state for later restoration after each contingency
            Map<LfBus, BusState> busStates = BusState.createBusStates(network.getBuses());
            for (LfBus bus : network.getBuses()) {
                bus.setVoltageControlSwitchOffCount(0);
            }

            // start a simulation for each of the contingency
            Iterator<LfContingency> contingencyIt = contingencies.iterator();
            while (contingencyIt.hasNext()) {
                LfContingency lfContingency = contingencyIt.next();

                for (LfBus bus : lfContingency.getBuses()) {
                    bus.setDisabled(true);
                }

                distributedMismatch(network, lfContingency.getActivePowerLoss(), loadFlowParameters, openLoadFlowParameters);

                PostContingencyResult postContingencyResult = runPostContingencySimulation(network, engine, lfContingency);
                postContingencyResults.add(postContingencyResult);

                if (contingencyIt.hasNext()) {
                    LOGGER.info("Restore pre-contingency state");

                    // restore base state
                    BusState.restoreBusStates(busStates, engine);
                }
            }
        }

        return new SecurityAnalysisResult(preContingencyResult, postContingencyResults);
    }

    public static void distributedMismatch(LfNetwork network, double mismatch, LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters openLoadFlowParameters) {
        if (loadFlowParameters.isDistributedSlack() && Math.abs(mismatch) > 0) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(loadFlowParameters.getBalanceType(), openLoadFlowParameters.isLoadPowerFactorConstant());
            activePowerDistribution.run(network, mismatch);
        }
    }

    private PostContingencyResult runPostContingencySimulation(LfNetwork network, AcloadFlowEngine engine, LfContingency lfContingency) {
        LOGGER.info("Start post contingency '{}' simulation", lfContingency.getContingency().getId());

        Stopwatch stopwatch = Stopwatch.createStarted();

        List<Equation> deactivatedEquations = new ArrayList<>();
        List<EquationTerm> deactivatedEquationTerms = new ArrayList<>();

        deactivateEquations(lfContingency, engine.getEquationSystem(), deactivatedEquations, deactivatedEquationTerms);

        // restart LF on post contingency equation system
        engine.getParameters().setVoltageInitializer(new PreviousValueVoltageInitializer());
        AcLoadFlowResult postContingencyLoadFlowResult = engine.run();
        boolean postContingencyComputationOk = postContingencyLoadFlowResult.getNewtonRaphsonStatus() == NewtonRaphsonStatus.CONVERGED;
        List<LimitViolation> postContingencyLimitViolations = new ArrayList<>();
        if (postContingencyComputationOk) {
            detectViolations(
                network.getBranches().stream().filter(b -> !lfContingency.getBranches().contains(b)),
                network.getBuses().stream().filter(b -> !lfContingency.getBuses().contains(b)),
                postContingencyLimitViolations);
        }

        reactivateEquations(deactivatedEquations, deactivatedEquationTerms);

        stopwatch.stop();
        LOGGER.info("Post contingency '{}' simulation done in {} ms", lfContingency.getContingency().getId(),
                stopwatch.elapsed(TimeUnit.MILLISECONDS));

        return new PostContingencyResult(lfContingency.getContingency(), postContingencyComputationOk, postContingencyLimitViolations);
    }

    private void deactivateEquations(LfContingency lfContingency, EquationSystem equationSystem, List<Equation> deactivatedEquations, List<EquationTerm> deactivatedEquationTerms) {
        for (LfBranch branch : lfContingency.getBranches()) {
            LOGGER.trace("Remove equations and equations terms related to branch '{}'", branch.getId());

            // deactivate all equations related to a branch
            for (Equation equation : equationSystem.getEquations(SubjectType.BRANCH, branch.getNum())) {
                if (equation.isActive()) {
                    equation.setActive(false);
                    deactivatedEquations.add(equation);
                }
            }

            // deactivate all equation terms related to a branch
            for (EquationTerm equationTerm : equationSystem.getEquationTerms(SubjectType.BRANCH, branch.getNum())) {
                if (equationTerm.isActive()) {
                    equationTerm.setActive(false);
                    deactivatedEquationTerms.add(equationTerm);
                }
            }
        }

        for (LfBus bus : lfContingency.getBuses()) {
            LOGGER.trace("Remove equations and equation terms related to bus '{}'", bus.getId());

            // deactivate all equations related to a bus
            for (Equation equation : equationSystem.getEquations(SubjectType.BUS, bus.getNum())) {
                if (equation.isActive()) {
                    equation.setActive(false);
                    deactivatedEquations.add(equation);
                }
            }

            // deactivate all equation terms related to a bus
            for (EquationTerm equationTerm : equationSystem.getEquationTerms(SubjectType.BUS, bus.getNum())) {
                if (equationTerm.isActive()) {
                    equationTerm.setActive(false);
                    deactivatedEquationTerms.add(equationTerm);
                }
            }
        }
    }

    private void reactivateEquations(List<Equation> deactivatedEquations, List<EquationTerm> deactivatedEquationTerms) {
        // restore deactivated equations and equations terms from previous contingency
        if (!deactivatedEquations.isEmpty()) {
            for (Equation equation : deactivatedEquations) {
                equation.setActive(true);
            }
            deactivatedEquations.clear();
        }
        if (!deactivatedEquationTerms.isEmpty()) {
            for (EquationTerm equationTerm : deactivatedEquationTerms) {
                equationTerm.setActive(true);
            }
            deactivatedEquationTerms.clear();
        }
    }

    List<LfContingency> createContingencies(List<ContingencyContext> contingencyContexts, LfNetwork network) {
        return createContingencies(contingencyContexts, network, createConnectivity(network));
    }

    public static List<LfContingency> createContingencies(List<ContingencyContext> contingencyContexts, LfNetwork network, GraphDecrementalConnectivity<LfBus> connectivity) {
        List<LfContingency> contingencies = new ArrayList<>();
        Iterator<ContingencyContext> contingencyContextIt = contingencyContexts.iterator();
        while (contingencyContextIt.hasNext()) {
            ContingencyContext contingencyContext = contingencyContextIt.next();

            // find contingency branches that are part of this network
            Set<LfBranch> branches = new HashSet<>(1);
            Iterator<String> branchIt = contingencyContext.getBranchIdsToOpen().iterator();
            while (branchIt.hasNext()) {
                String branchId = branchIt.next();
                LfBranch branch = network.getBranchById(branchId);
                if (branch != null) {
                    branches.add(branch);
                    branchIt.remove();
                }
            }

            // if no more branch in the contingency, remove contingency from the list because
            // it won't be part of another network
            if (contingencyContext.getBranchIdsToOpen().isEmpty()) {
                contingencyContextIt.remove();
            }

            // check if contingency split this network into multiple components
            if (branches.isEmpty()) {
                continue;
            }

            // update connectivity with triggered branches
            for (LfBranch branch : branches) {
                connectivity.cut(branch.getBus1(), branch.getBus2());
            }

            // add to contingency description buses and branches that won't be part of the main connected
            // component in post contingency state
            Set<LfBus> buses = connectivity.getSmallComponents().stream().flatMap(Set::stream).collect(Collectors.toSet());
            buses.forEach(b -> branches.addAll(b.getBranches()));

            // reset connectivity to discard triggered branches
            connectivity.reset();

            contingencies.add(new LfContingency(contingencyContext.getContingency(), buses, branches));
        }

        return contingencies;
    }

    private GraphDecrementalConnectivity<LfBus> createConnectivity(LfNetwork network) {
        GraphDecrementalConnectivity<LfBus> connectivity = connectivityProvider.get();
        for (LfBus bus : network.getBuses()) {
            connectivity.addVertex(bus);
        }
        for (LfBranch branch : network.getBranches()) {
            connectivity.addEdge(branch.getBus1(), branch.getBus2());
        }
        return connectivity;
    }

}
