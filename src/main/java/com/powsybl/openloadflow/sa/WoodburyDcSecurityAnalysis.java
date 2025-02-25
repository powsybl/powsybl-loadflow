/*
 * Copyright (c) 2024-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.google.common.base.Stopwatch;
import com.powsybl.action.Action;
import com.powsybl.action.PhaseTapChangerTapPositionAction;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.dc.fastdc.*;
import com.powsybl.openloadflow.dc.fastdc.ConnectivityBreakAnalysis.ConnectivityAnalysisResult;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.action.AbstractLfTapChangerAction;
import com.powsybl.openloadflow.network.action.LfAction;
import com.powsybl.openloadflow.network.action.LfActionUtils;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import com.powsybl.security.LimitViolationsResult;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.limitreduction.LimitReduction;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.*;
import com.powsybl.security.strategy.OperatorStrategy;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
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
    protected DcLoadFlowParameters createParameters(LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, boolean breakers, boolean areas) {
        DcLoadFlowParameters dcParameters = super.createParameters(lfParameters, lfParametersExt, breakers, areas);
        LfNetworkParameters lfNetworkParameters = dcParameters.getNetworkParameters();
        boolean hasDroopControl = lfNetworkParameters.isHvdcAcEmulation() && network.getHvdcLineStream().anyMatch(l -> {
            HvdcAngleDroopActivePowerControl droopControl = l.getExtension(HvdcAngleDroopActivePowerControl.class);
            return droopControl != null && droopControl.isEnabled();
        });
        if (hasDroopControl) {
            Reports.reportAcEmulationDisabledInWoodburyDcSecurityAnalysis(reportNode);
        }
        lfNetworkParameters.setMinImpedance(true) // connectivity break analysis does not handle zero impedance lines
                           .setHvdcAcEmulation(false); // ac emulation is not yet supported
        // needed an equation to force angle to zero when a PST is lost
        dcParameters.getEquationSystemCreationParameters().setForcePhaseControlOffAndAddAngle1Var(true);
        return dcParameters;
    }

    /**
     * Calculate post contingency states for a contingency.
     * In case of connectivity break, a pre-computation is done to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency, the pre contingency flowStates are overridden.
     * @return the post contingency states for the contingency.
     */
    private double[] calculatePostContingencyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                    ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                    ReportNode reportNode) {
        return calculatePostContingencyAndOperatorStrategyStates(loadFlowContext, contingenciesStates, flowStates, connectivityAnalysisResult, contingencyElementByBranch,
                Collections.emptyList(), Collections.emptyMap(), DenseMatrix.EMPTY, reportNode);
    }

    /**
     * Calculate post contingency and post operator strategy states, for a contingency and operator strategy actions.
     * In case of connectivity break, a pre-computation is done to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost/modified due to the contingency/operator strategy, the pre contingency flowStates are overridden.
     * @return the post contingency and operator strategy states.
     */
    private double[] calculatePostContingencyAndOperatorStrategyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                                       ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                                       List<LfAction> operatorStrategyLfActions, Map<String, ComputedTapPositionChangeElement> tapPositionChangeElementByBranch,
                                                                       DenseMatrix actionsStates, ReportNode reportNode) {
        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();
        Set<LfBus> disabledBuses = connectivityAnalysisResult.getDisabledBuses();
        Set<LfBranch> partialDisabledBranches = connectivityAnalysisResult.getPartialDisabledBranches();
        Set<String> elementsToReconnect = connectivityAnalysisResult.getElementsToReconnect();

        // reset active flow of hvdc line without power
        connectivityAnalysisResult.getHvdcsWithoutPower().forEach(hvdcWithoutPower -> {
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation1().getId());
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation2().getId());
        });

        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !elementsToReconnect.contains(element))
                .map(contingencyElementByBranch::get)
                .collect(Collectors.toList());
        List<ComputedTapPositionChangeElement> actionElements = operatorStrategyLfActions.stream()
                .filter(AbstractLfTapChangerAction.class::isInstance)
                .map(lfAction -> ((AbstractLfTapChangerAction<?>) lfAction).getChange().getBranch().getId())
                .map(tapPositionChangeElementByBranch::get)
                .collect(Collectors.toList());

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(partialDisabledBranches);
        DisabledNetwork disabledNetwork = new DisabledNetwork(disabledBuses, disabledBranches);

        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates, actionElements, actionsStates);
        double[] newFlowStates = flowStates;
        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToLose().isEmpty()) {

            // get the lost phase tap changers for this contingency
            Set<LfBranch> lostPhaseControllers = contingency.getBranchIdsToOpen().keySet().stream()
                    .filter(element -> !elementsToReconnect.contains(element))
                    .map(contingencyElementByBranch::get)
                    .map(ComputedContingencyElement::getLfBranch)
                    .filter(LfBranch::hasPhaseControllerCapability)
                    .collect(Collectors.toSet());

            // if a phase tap changer is lost or if the connectivity have changed, we must recompute load flows
            // same if there is an action, as they are only on pst for now
            if (!disabledBuses.isEmpty() || !lostPhaseControllers.isEmpty() || !operatorStrategyLfActions.isEmpty()) {
                newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, reportNode, operatorStrategyLfActions);
            }
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save base state for later restoration after each contingency
            DcLoadFlowParameters lfParameters = loadFlowContext.getParameters();
            NetworkState networkState = NetworkState.save(lfNetwork);
            connectivityAnalysisResult.toLfContingency()
                    .ifPresent(lfContingency -> lfContingency.apply(lfParameters.getBalanceType()));
            newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, reportNode, operatorStrategyLfActions);
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
            networkState.restore();
        }

        return newFlowStates;
    }

    private void filterActions(List<Action> actions) {
        actions.stream()
                .filter(action -> !(action instanceof PhaseTapChangerTapPositionAction))
                .findAny()
                .ifPresent(e -> {
                    throw new IllegalStateException("For now, only PhaseTapChangerTapPositionAction is allowed in WoodburyDcSecurityAnalysis");
                });
    }

    /**
     * Returns the post contingency result associated to given contingency and post contingency states.
     */
    private PostContingencyResult computePostContingencyResultFromPostContingencyStates(DcLoadFlowContext loadFlowContext, Contingency contingency, LfContingency lfContingency,
                                                                                        LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
                                                                                        boolean createResultExtension, SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters,
                                                                                        double[] postContingencyStates, List<LimitReduction> limitReductions) {

        // update network state with post contingency states
        LfNetwork lfNetwork = loadFlowContext.getNetwork();
        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);
        updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyStates);

        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());

        // update post contingency network result
        var postContingencyNetworkResult = new PostContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension, preContingencyNetworkResult, contingency);
        postContingencyNetworkResult.update();

        // detect violations
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, violationsParameters);
        postContingencyLimitViolationManager.detectViolations(lfNetwork);

        var connectivityResult = new ConnectivityResult(
                lfContingency.getCreatedSynchronousComponentsCount(), 0,
                lfContingency.getDisconnectedLoadActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedGenerationActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedElementIds());

        return new PostContingencyResult(contingency,
                PostContingencyComputationStatus.CONVERGED,
                new LimitViolationsResult(postContingencyLimitViolationManager.getLimitViolations()),
                postContingencyNetworkResult.getBranchResults(),
                postContingencyNetworkResult.getBusResults(),
                postContingencyNetworkResult.getThreeWindingsTransformerResults(),
                connectivityResult);
    }

    /**
     * Returns the operator strategy result associated to the given post contingency and post operator strategy states.
     */
    private OperatorStrategyResult computeOperatorStrategyResultFromPostContingencyAndOperatorStrategyStates(DcLoadFlowContext loadFlowContext, LfContingency lfContingency, OperatorStrategy operatorStrategy,
                                                                                                             List<LfAction> operatorStrategyLfActions, LimitViolationManager preContingencyLimitViolationManager,
                                                                                                             boolean createResultExtension, SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters,
                                                                                                             double[] postContingencyAndOperatorStrategyStates, List<LimitReduction> limitReductions) {
        // update network state with post contingency and post operator strategy states
        LfNetwork lfNetwork = loadFlowContext.getNetwork();
        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyAndOperatorStrategyStates);
        updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyAndOperatorStrategyStates);

        // apply modifications to compute results
        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());
        LfActionUtils.applyListOfActions(operatorStrategyLfActions, lfNetwork, lfContingency, loadFlowContext.getParameters().getNetworkParameters(), reportNode);

        // update network result
        var postActionsNetworkResult = new PreContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension);
        postActionsNetworkResult.update();

        // detect violations
        var postActionsViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, violationsParameters);
        postActionsViolationManager.detectViolations(lfNetwork);

        return new OperatorStrategyResult(operatorStrategy, PostContingencyComputationStatus.CONVERGED,
                new LimitViolationsResult(postActionsViolationManager.getLimitViolations()),
                new NetworkResult(postActionsNetworkResult.getBranchResults(),
                        postActionsNetworkResult.getBusResults(),
                        postActionsNetworkResult.getThreeWindingsTransformerResults()));
    }

    /**
     * Add the post contingency and operator strategy results, associated to given contingency, in the given list of results.
     * The post contingency and post operator strategy states are computed with given supplier and function.
     */
    private void addPostContingencyAndOperatorStrategyResults(DcLoadFlowContext context, ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, List<OperatorStrategy>> operatorStrategiesByContingencyId,
                                                              Map<String, LfAction> lfActionById, Function<ConnectivityAnalysisResult, double[]> toPostContingencyStates,
                                                              BiFunction<ConnectivityAnalysisResult, List<LfAction>, double[]> toPostContingencyAndOperatorStrategyStates,
                                                              Runnable restorePreContingencyStates, LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
                                                              boolean createResultExtension, SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters, List<LimitReduction> limitReductions,
                                                              List<PostContingencyResult> postContingencyResults, List<OperatorStrategyResult> operatorStrategyResults) {
        // process results only if contingency impacts the network
        connectivityAnalysisResult.toLfContingency().ifPresent(lfContingency -> {
            LfNetwork lfNetwork = context.getNetwork();

            Contingency contingency = connectivityAnalysisResult.getPropagatedContingency().getContingency();
            ReportNode postContSimReportNode = Reports.createPostContingencySimulation(lfNetwork.getReportNode(), contingency.getId());
            lfNetwork.setReportNode(postContSimReportNode);

            // process post contingency result with supplier giving post contingency states
            logPostContingencyStart(context.getNetwork(), lfContingency);
            Stopwatch stopwatch = Stopwatch.createStarted();

            double[] postContingencyStates = toPostContingencyStates.apply(connectivityAnalysisResult);
            PostContingencyResult postContingencyResult = computePostContingencyResultFromPostContingencyStates(context, contingency,
                    lfContingency, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                    violationsParameters, postContingencyStates, limitReductions);

            stopwatch.stop();
            logPostContingencyEnd(context.getNetwork(), lfContingency, stopwatch);
            postContingencyResults.add(postContingencyResult);

            // restore pre contingency states for next calculation
            restorePreContingencyStates.run();

            List<OperatorStrategy> operatorStrategiesForThisContingency = operatorStrategiesByContingencyId.get(contingency.getId());
            if (operatorStrategiesForThisContingency != null) {
                for (OperatorStrategy operatorStrategy : operatorStrategiesForThisContingency) {
                    ReportNode osSimReportNode = Reports.createOperatorStrategySimulation(postContSimReportNode, operatorStrategy.getId());
                    lfNetwork.setReportNode(osSimReportNode);

                    // get the actions associated to the operator strategy
                    List<String> actionIds = checkCondition(operatorStrategy, postContingencyResult.getLimitViolationsResult());
                    List<LfAction> operatorStrategyLfActions = actionIds.stream()
                            .map(lfActionById::get)
                            .filter(Objects::nonNull)
                            .toList();

                    logActionStart(context.getNetwork(), operatorStrategy);
                    stopwatch = Stopwatch.createStarted();

                    // process operator strategy result with supplier giving post contingency and post operator strategy states
                    double[] postContingencyAndOperatorStrategyStates = toPostContingencyAndOperatorStrategyStates.apply(connectivityAnalysisResult, operatorStrategyLfActions);
                    OperatorStrategyResult operatorStrategyResult = computeOperatorStrategyResultFromPostContingencyAndOperatorStrategyStates(context, lfContingency, operatorStrategy, operatorStrategyLfActions,
                            preContingencyLimitViolationManager, createResultExtension, violationsParameters, postContingencyAndOperatorStrategyStates, limitReductions);

                    stopwatch.stop();
                    logActionEnd(context.getNetwork(), operatorStrategy, stopwatch);
                    operatorStrategyResults.add(operatorStrategyResult);

                    // restore pre contingency states for next calculation
                    restorePreContingencyStates.run();
                }
            }
        });
    }

    private static Map<String, ComputedTapPositionChangeElement> createTapPositionChangeElementsIndexByBranchId(Map<String, LfAction> lfActionById, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        Map<String, ComputedTapPositionChangeElement> computedTapPositionChangeElements = lfActionById.values().stream()
                .filter(AbstractLfTapChangerAction.class::isInstance)
                .map(lfAction -> new ComputedTapPositionChangeElement(((AbstractLfTapChangerAction<?>) lfAction).getChange(), equationSystem))
                .filter(computedTapPositionChangeElement -> computedTapPositionChangeElement.getLfBranchEquation() != null)
                .collect(Collectors.toMap(
                        computedTapPositionChangeElement -> computedTapPositionChangeElement.getLfBranch().getId(),
                        computedTapPositionChangeElement -> computedTapPositionChangeElement,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        ComputedElement.setComputedElementIndexes(computedTapPositionChangeElements.values());
        return computedTapPositionChangeElements;
    }

    @Override
    protected SecurityAnalysisResult runSimulations(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies, DcLoadFlowParameters dcParameters,
                                                    SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                    List<Action> actions, List<LimitReduction> limitReductions, ContingencyActivePowerLossDistribution contingencyActivePowerLossDistribution) {
        // Verify only PST actions are given
        filterActions(actions);
        Map<String, Action> actionsById = indexActionsById(actions);
        Set<Action> neededActions = new HashSet<>(actionsById.size());
        Map<String, List<OperatorStrategy>> operatorStrategiesByContingencyId =
                indexOperatorStrategiesByContingencyId(propagatedContingencies, operatorStrategies, actionsById, neededActions, true);
        Map<String, LfAction> lfActionById = createLfActions(lfNetwork, neededActions, network, dcParameters.getNetworkParameters()); // only convert needed actions

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        boolean createResultExtension = openSecurityAnalysisParameters.isCreateResultExtension();

        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters, false)) {
            ReportNode networkReportNode = lfNetwork.getReportNode();
            ReportNode preContSimReportNode = Reports.createPreContingencySimulation(networkReportNode);
            lfNetwork.setReportNode(preContSimReportNode);

            // prepare contingencies for connectivity analysis and Woodbury engine
            // note that contingencies on branches connected only on one side are removed,
            // this is a difference with dc security analysis
            cleanContingencies(lfNetwork, propagatedContingencies);

            // compute the pre-contingency states
            double[] preContingencyStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(context, new DisabledNetwork(), reportNode);
            // create workingContingencyStates that will be a working copy of pre-contingency states
            double[] workingContingencyStates = new double[preContingencyStates.length];
            System.arraycopy(preContingencyStates, 0, workingContingencyStates, 0, preContingencyStates.length);

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

            // compute states with +1 -1 to model the actions in Woodbury engine
            Map<String, ComputedTapPositionChangeElement> tapPositionChangeElementsByBranchId = createTapPositionChangeElementsIndexByBranchId(lfActionById, context.getEquationSystem());
            DenseMatrix actionsStates = ComputedElement.calculateElementsStates(context, tapPositionChangeElementsByBranchId.values());

            // save base state for later restoration after each contingency/action
            NetworkState networkState = NetworkState.save(lfNetwork);

            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();

            // supplier to compute post contingency states
            // no need to distribute active mismatch due to connectivity modifications
            // this is handled when the slack is distributed in pre contingency states override
            Function<ConnectivityBreakAnalysis.ConnectivityAnalysisResult, double[]> toPostContingencyStates =
                    connectivityAnalysisResult -> calculatePostContingencyStates(context, connectivityBreakAnalysisResults.contingenciesStates(), workingContingencyStates,
                    connectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(), reportNode);
            // function to compute post contingency and post operator strategy states
            BiFunction<ConnectivityBreakAnalysis.ConnectivityAnalysisResult, List<LfAction>, double[]> toPostContingencyAndOperatorStrategyStates =
                    (connectivityAnalysisResult, operatorStrategyLfActions) -> calculatePostContingencyAndOperatorStrategyStates(context, connectivityBreakAnalysisResults.contingenciesStates(), workingContingencyStates,
                    connectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(), operatorStrategyLfActions, tapPositionChangeElementsByBranchId, actionsStates, reportNode);

            LOGGER.info("Processing post contingency results for contingencies with no connectivity break");
            connectivityBreakAnalysisResults.nonBreakingConnectivityContingencies().forEach(nonBreakingConnectivityContingency -> {
                ConnectivityBreakAnalysis.ConnectivityAnalysisResult connectivityAnalysisResult = new ConnectivityBreakAnalysis.ConnectivityAnalysisResult(nonBreakingConnectivityContingency, lfNetwork);
                // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                Runnable restorePreContingencyStates = () -> {
                    // update workingContingencyStates as it may have been updated by post contingency states calculation
                    System.arraycopy(preContingencyStates, 0, workingContingencyStates, 0, preContingencyStates.length);
                    // restore pre contingency state
                    networkState.restore();
                };
                addPostContingencyAndOperatorStrategyResults(context, connectivityAnalysisResult, operatorStrategiesByContingencyId, lfActionById, toPostContingencyStates,
                        toPostContingencyAndOperatorStrategyStates, restorePreContingencyStates, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                        securityAnalysisParameters.getIncreasedViolationsParameters(), limitReductions, postContingencyResults, operatorStrategyResults);
            });

            LOGGER.info("Processing post contingency results for contingencies breaking connectivity");
            connectivityBreakAnalysisResults.connectivityAnalysisResults().forEach(connectivityAnalysisResult -> {
                // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                // no need to update workingContingencyStates as an override of flow states will be computed
                Runnable restorePreContingencyStates = networkState::restore;
                addPostContingencyAndOperatorStrategyResults(context, connectivityAnalysisResult, operatorStrategiesByContingencyId, lfActionById, toPostContingencyStates,
                        toPostContingencyAndOperatorStrategyStates, restorePreContingencyStates, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                        securityAnalysisParameters.getIncreasedViolationsParameters(), limitReductions, postContingencyResults, operatorStrategyResults);
            });

            return new SecurityAnalysisResult(
                    new PreContingencyResult(LoadFlowResult.ComponentResult.Status.CONVERGED,
                            new LimitViolationsResult(preContingencyLimitViolationManager.getLimitViolations()),
                            preContingencyNetworkResult.getBranchResults(), preContingencyNetworkResult.getBusResults(),
                            preContingencyNetworkResult.getThreeWindingsTransformerResults()),
                            postContingencyResults, operatorStrategyResults);
        }
    }
}
