/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.Action;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.sensi.ComputedContingencyElement;
import com.powsybl.openloadflow.sensi.ConnectivityBreakAnalysis;
import com.powsybl.openloadflow.sensi.WoodburyEngine;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import com.powsybl.security.LimitViolationsResult;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.limitreduction.LimitReduction;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.ConnectivityResult;
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.results.PreContingencyResult;
import com.powsybl.security.strategy.OperatorStrategy;

import java.util.*;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.dc.DcLoadFlowEngine.updateNetwork;
import static com.powsybl.openloadflow.network.impl.PropagatedContingency.cleanContingencies;

/**
 * @author Pierre Arvy {@literal <pierre.arvy at artelys.com>}
 */
public class WoodburyDcSecurityAnalysis extends DcSecurityAnalysis {

    protected WoodburyDcSecurityAnalysis(Network network, MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                         List<StateMonitor> stateMonitors, ReportNode reportNode) {
        super(network, matrixFactory, connectivityFactory, stateMonitors, reportNode);
    }

    @Override
    protected ReportNode createSaRootReportNode() {
        return Reports.createWoodburyDcSecurityAnalysis(reportNode, network.getId());
    }

    @Override
    protected DcLoadFlowParameters createParameters(LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, boolean breakers) {
        DcLoadFlowParameters dcParameters = super.createParameters(lfParameters, lfParametersExt, breakers);
        dcParameters.getNetworkParameters().setMinImpedance(true); // Connectivity break analysis does not handle zero impedance lines
        dcParameters.getEquationSystemCreationParameters().setForcePhaseControlOffAndAddAngle1Var(true); // Needed to force at 0 a PST shifting angle when a PST is lost
        return dcParameters;
    }

    /**
     * Calculate post contingency states for a contingency.
     * In case of connectivity break, a pre-computation has been done in {@link #calculatePostContingencyStatesForAContingencyBreakingConnectivity}
     * to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency, the pre contingency flowStates are overridden.
     */
    private double[] calculatePostContingencyStatesForAContingency(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                                   PropagatedContingency contingency, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                                   Set<LfBus> disabledBuses, Set<String> elementsToReconnect, ReportNode reportNode, Set<LfBranch> partialDisabledBranches) {

        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !elementsToReconnect.contains(element))
                .map(contingencyElementByBranch::get)
                .collect(Collectors.toList());

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(partialDisabledBranches);
        DisabledNetwork disabledNetwork = new DisabledNetwork(disabledBuses, disabledBranches);

        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates);
        double[] postContingencyStates;
        double[] newFlowStates = flowStates;
        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToLoose().isEmpty()) {

            // get the lost phase tap changers for this contingency
            Set<LfBranch> lostPhaseControllers = contingency.getBranchIdsToOpen().keySet().stream()
                    .filter(element -> !elementsToReconnect.contains(element))
                    .map(contingencyElementByBranch::get)
                    .map(ComputedContingencyElement::getLfBranch)
                    .filter(LfBranch::hasPhaseControllerCapability)
                    .collect(Collectors.toSet());

            // if a phase tap changer is lost or if the connectivity have changed, we must recompute load flows
            if (!disabledBuses.isEmpty() || !lostPhaseControllers.isEmpty()) {
                newFlowStates = DcLoadFlowEngine.run(loadFlowContext, disabledNetwork, reportNode);
            }
            postContingencyStates = engine.run(newFlowStates);
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save base state for later restoration after each contingency
            DcLoadFlowParameters lfParameters = loadFlowContext.getParameters();
            NetworkState networkState = NetworkState.save(lfNetwork);
            contingency.toLfContingency(lfNetwork)
                    .ifPresent(lfContingency -> lfContingency.apply(lfParameters.getBalanceType()));
            newFlowStates = DcLoadFlowEngine.run(loadFlowContext, disabledNetwork, reportNode);
            postContingencyStates = engine.run(newFlowStates);
            networkState.restore();
        }

        return postContingencyStates;
    }

    /**
     * Calculate post contingency states for a contingency breaking connectivity.
     */
    private double[] calculatePostContingencyStatesForAContingencyBreakingConnectivity(ConnectivityBreakAnalysis.ConnectivityAnalysisResult connectivityAnalysisResult, DcLoadFlowContext loadFlowContext,
                                                                                      Map<String, ComputedContingencyElement> contingencyElementByBranch, double[] flowStates, DenseMatrix contingenciesStates,
                                                                                      ReportNode reportNode) {

        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();
        Set<LfBus> disabledBuses = connectivityAnalysisResult.getDisabledBuses();

        // as we are processing a contingency with connectivity break, we have to reset active power flow of a hvdc line
        // if one bus of the line is lost.
        for (LfHvdc hvdc : loadFlowContext.getNetwork().getHvdcs()) {
            if (Networks.isIsolatedBusForHvdc(hvdc.getBus1(), disabledBuses) ^ Networks.isIsolatedBusForHvdc(hvdc.getBus2(), disabledBuses)) {
                contingency.getGeneratorIdsToLose().add(hvdc.getConverterStation1().getId());
                contingency.getGeneratorIdsToLose().add(hvdc.getConverterStation2().getId());
            }
        }

        return calculatePostContingencyStatesForAContingency(loadFlowContext, contingenciesStates, flowStates,
                contingency, contingencyElementByBranch, disabledBuses, connectivityAnalysisResult.getElementsToReconnect(), reportNode,
                connectivityAnalysisResult.getPartialDisabledBranches());
    }

    private PostContingencyResult computePostContingencyResult(DcLoadFlowContext loadFlowContext, SecurityAnalysisParameters securityAnalysisParameters,
                                                               LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
                                                               PropagatedContingency contingency, double[] postContingencyStates,
                                                               List<LimitReduction> limitReductions, boolean createResultExtension) {

        // update network state with post contingency states
        LfNetwork lfNetwork = loadFlowContext.getNetwork();
        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);
        updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyStates);

        // apply lf contingency to compute post contingency results
        LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElseThrow(); // the contingency can not be null
        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());

        // update post contingency network result
        var postContingencyNetworkResult = new PostContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension, preContingencyNetworkResult, contingency.getContingency());
        postContingencyNetworkResult.update();

        // detect violations
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, securityAnalysisParameters.getIncreasedViolationsParameters());
        postContingencyLimitViolationManager.detectViolations(lfNetwork);

        // connectivity result due to the application of the lf contingency
        var connectivityResult = new ConnectivityResult(
                lfContingency.getCreatedSynchronousComponentsCount(), 0,
                lfContingency.getDisconnectedLoadActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedGenerationActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedElementIds());

        return new PostContingencyResult(contingency.getContingency(),
                PostContingencyComputationStatus.CONVERGED, new LimitViolationsResult(postContingencyLimitViolationManager.getLimitViolations()),
                postContingencyNetworkResult.getBranchResults(),
                postContingencyNetworkResult.getBusResults(),
                postContingencyNetworkResult.getThreeWindingsTransformerResults(),
                connectivityResult);
    }

    @Override
    protected SecurityAnalysisResult runSimulations(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies, DcLoadFlowParameters dcParameters,
                                                    SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                    List<Action> actions, List<LimitReduction> limitReductions) {
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        boolean createResultExtension = openSecurityAnalysisParameters.isCreateResultExtension();

        try (DcLoadFlowContext context = createLoadFlowContext(lfNetwork, dcParameters)) {
            ReportNode networkReportNode = lfNetwork.getReportNode();
            ReportNode preContSimReportNode = Reports.createPreContingencySimulation(networkReportNode);
            lfNetwork.setReportNode(preContSimReportNode);

            // prepare contingencies for connectivity analysis and Woodbury engine
            // note that contingencies on branches connected only on one side are removed,
            // this is a difference with dc security analysis
            cleanContingencies(lfNetwork, propagatedContingencies);

            double[] preContingencyStates = DcLoadFlowEngine.run(context, new DisabledNetwork(), reportNode);

            // set pre contingency angle states as state vector of equation system
            context.getEquationSystem().getStateVector().set(preContingencyStates);

            // Update network voltages with pre contingency states
            updateNetwork(lfNetwork, context.getEquationSystem(), preContingencyStates);
            if (context.getParameters().isSetVToNan()) {
                for (LfBus bus : lfNetwork.getBuses()) {
                    bus.setV(Double.NaN);
                }
            }

            // update network result
            var preContingencyNetworkResult = new PreContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension);
            preContingencyNetworkResult.update();

            // detect violations
            var preContingencyLimitViolationManager = new LimitViolationManager(limitReductions);
            preContingencyLimitViolationManager.detectViolations(lfNetwork);

            // compute states with +1 -1 to model the contingencies and run connectivity analysis
            ConnectivityBreakAnalysis.ConnectivityBreakAnalysisResults connectivityBreakAnalysisResults = ConnectivityBreakAnalysis.run(context, propagatedContingencies);

            // save base state for later restoration after each contingency
            NetworkState networkState = NetworkState.save(lfNetwork);

            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            LOGGER.info("Processing post contingency results for contingencies with no connectivity break");
            connectivityBreakAnalysisResults.nonBreakingConnectivityContingencies()
                    .forEach(contingency -> contingency.toLfContingency(lfNetwork)
                        .ifPresent(lfContingency -> {
                            logContingency(lfNetwork, lfContingency);
                            double[] postContingencyStates = calculatePostContingencyStatesForAContingency(context, connectivityBreakAnalysisResults.contingenciesStates(), preContingencyStates, contingency,
                                    connectivityBreakAnalysisResults.contingencyElementByBranch(), Collections.emptySet(), Collections.emptySet(), reportNode, Collections.emptySet());
                            // compute post contingency result with post contingency states
                            PostContingencyResult postContingencyResult = computePostContingencyResult(context, securityAnalysisParameters, preContingencyLimitViolationManager,
                                    preContingencyNetworkResult, contingency, postContingencyStates, limitReductions, createResultExtension);
                            postContingencyResults.add(postContingencyResult);
                            networkState.restore();
                        })
                );

            LOGGER.info("Processing post contingency results for contingencies breaking connectivity");
            connectivityBreakAnalysisResults.connectivityAnalysisResults()
                    .forEach(connectivityAnalysisResult -> {
                        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();
                        contingency.toLfContingency(lfNetwork)
                            .ifPresent(lfContingency -> {
                                logContingency(lfNetwork, lfContingency);
                                // no need to distribute active mismatch due to connectivity modifications
                                // this is handled when the slack is distributed in pre contingency states override
                                double[] postContingencyStates = calculatePostContingencyStatesForAContingencyBreakingConnectivity(connectivityAnalysisResult, context,
                                        connectivityBreakAnalysisResults.contingencyElementByBranch(), preContingencyStates,
                                        connectivityBreakAnalysisResults.contingenciesStates(), reportNode);
                                // compute post contingency result with post contingency states
                                PostContingencyResult postContingencyResult = computePostContingencyResult(context, securityAnalysisParameters, preContingencyLimitViolationManager,
                                        preContingencyNetworkResult, contingency, postContingencyStates, limitReductions, createResultExtension);
                                postContingencyResults.add(postContingencyResult);
                                networkState.restore();
                            });
                    });

            return new SecurityAnalysisResult(
                    new PreContingencyResult(LoadFlowResult.ComponentResult.Status.CONVERGED,
                            new LimitViolationsResult(preContingencyLimitViolationManager.getLimitViolations()),
                            preContingencyNetworkResult.getBranchResults(), preContingencyNetworkResult.getBusResults(),
                            preContingencyNetworkResult.getThreeWindingsTransformerResults()),
                    postContingencyResults, new ArrayList<>());
        }
    }
}
