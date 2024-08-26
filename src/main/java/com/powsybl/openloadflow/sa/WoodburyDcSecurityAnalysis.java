/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.Action;
import com.powsybl.action.PhaseTapChangerTapPositionAction;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.sensi.*;
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
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.dc.DcLoadFlowEngine.updateNetwork;
import static com.powsybl.openloadflow.sensi.DcSensitivityAnalysis.cleanContingencies;

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

    /**
     * Calculate post contingency states for a contingency.
     * In case of connectivity break, a pre-computation has been done in {@link #calculatePostContingencyStatesForAContingencyBreakingConnectivity}
     * to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency, the pre contingency flowStates are overridden.
     */
    // TODO : add action in the parameters of the method
    private double[] calculatePostContingencyStatesForAContingency(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                                      PropagatedContingency contingency, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                                      Set<LfBus> disabledBuses, Set<String> elementsToReconnect, ReportNode reportNode,
                                                                   Set<LfBranch> partialDisabledBranches, List<LfAction> lfActions, DenseMatrix actionsStates) {

        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !elementsToReconnect.contains(element))
                .map(contingencyElementByBranch::get)
                .collect(Collectors.toList());

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(partialDisabledBranches);
        DisabledNetwork disabledNetwork = new DisabledNetwork(disabledBuses, disabledBranches);

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
            // same with there is an action, as they are only on pst for now
            if (!disabledBuses.isEmpty() || !lostPhaseControllers.isEmpty() || !lfActions.isEmpty()) {
                newFlowStates = DcSensitivityAnalysis.runDcLoadFlow(loadFlowContext, disabledNetwork, reportNode, lfActions); // TODO : run dc lf taking action into account
            }
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save base state for later restoration after each contingency
            // nothing to do for actions as they only apply on pst
            DcLoadFlowParameters lfParameters = loadFlowContext.getParameters();
            NetworkState networkState = NetworkState.save(lfNetwork);
            contingency.toLfContingency(lfNetwork)
                    .ifPresent(lfContingency -> lfContingency.apply(lfParameters.getBalanceType()));
            newFlowStates = DcSensitivityAnalysis.runDcLoadFlow(loadFlowContext, disabledNetwork, reportNode, lfActions); // TODO : run dc lf taking into account the actions
            networkState.restore();
        }

        // TODO : add action states and the rest...
        List<ComputedActionElement> actionElements =
                lfActions.stream()
                        .map(action -> new ComputedActionElement(action, lfNetwork, loadFlowContext.getEquationSystem()))
                        .filter(element -> element.getLfBranchEquation() != null)
                        .collect(Collectors.toList());
        ComputedActionElement.setActionIndexes(actionElements);
//        NetworkState networkState = NetworkState.save(lfNetwork);
//        lfActions.forEach(action -> action.apply(loadFlowContext.getParameters().getNetworkParameters()));
//        DenseMatrix actionsStates = calculateActionsStates(loadFlowContext, actionElements);
//        networkState.restore();
        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates, actionElements, actionsStates); // TODO : compute for one contingency and one action
        return engine.run(newFlowStates);
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
                connectivityAnalysisResult.getPartialDisabledBranches(), List.of(), new DenseMatrix(0, 0));
    }

    /**
     * Filter the contingencies applied on the given network.
     * Contingencies on switch are not yet supported in {@link WoodburyDcSecurityAnalysis}.
     */
    private void filterPropagatedContingencies(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies) {

        // contingencies on switch not yet supported
        propagatedContingencies.stream()
                .flatMap(contingency -> contingency.getBranchIdsToOpen().keySet().stream())
                .map(branchId -> lfNetwork.getBranchById(branchId).getBranchType())
                .filter(branchType -> branchType == LfBranch.BranchType.SWITCH)
                .findAny()
                .ifPresent(e -> {
                    throw new IllegalArgumentException("Contingencies on switch not yet supported in fast DC Security Analysis");
                });
    }

    private void filterActions(List<Action> actions) {
        actions.stream()
                .filter(action -> !(action instanceof PhaseTapChangerTapPositionAction))
                .findAny()
                .ifPresent(e -> {
                    throw new IllegalArgumentException("Only PhaseTapChangerTapPositionAction is allowed");
                });
    }

    public static DenseMatrix initActionRhs(LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem, Collection<ComputedActionElement> actionElements) {
        // otherwise, defining the rhs matrix will result in integer overflow
        int equationCount = equationSystem.getIndex().getSortedEquationsToSolve().size();
        int maxContingencyElements = Integer.MAX_VALUE / (equationCount * Double.BYTES);
        if (actionElements.size() > maxContingencyElements) {
            throw new PowsyblException("Too many action elements " + actionElements.size()
                    + ", maximum is " + maxContingencyElements + " for a system with " + equationCount + " equations");
        }

        DenseMatrix rhs = new DenseMatrix(equationCount, actionElements.size());
        fillRhsAction(lfNetwork, equationSystem, actionElements, rhs);
        return rhs;
    }

    private static DenseMatrix calculateActionsStates(DcLoadFlowContext loadFlowContext, Collection<ComputedActionElement> actionElements) {
        DenseMatrix actionsStates = initActionRhs(loadFlowContext.getNetwork(), loadFlowContext.getEquationSystem(), actionElements); // rhs with +1 -1 on contingency elements
        loadFlowContext.getJacobianMatrix().solveTransposed(actionsStates);
        return actionsStates;
    }

    private static void fillRhsAction(LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                           Collection<ComputedActionElement> actionElements, Matrix rhs) {
        for (ComputedActionElement element : actionElements) {
            LfBranch lfBranch = element.getAction().getTapPositionChange().branch();
//            if (lfBranch.getBus1() == null || lfBranch.getBus2() == null) {
//                continue;
//            } // TODO : is this security necessary ?
            LfBus bus1 = lfBranch.getBus1();
            LfBus bus2 = lfBranch.getBus2();
            if (bus1.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getActionIndex(), -1);
            } else if (bus2.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getActionIndex(), 1);
            } else {
                Equation<DcVariableType, DcEquationType> p1 = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                Equation<DcVariableType, DcEquationType> p2 = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p1.getColumn(), element.getActionIndex(), 1);
                rhs.set(p2.getColumn(), element.getActionIndex(), -1);
            }
        }
    }

    private PostContingencyResult computePostContingencyResult(DcLoadFlowContext loadFlowContext, SecurityAnalysisParameters securityAnalysisParameters,
                                                               LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
                                                               PropagatedContingency contingency, double[] postContingencyStates,
                                                               List<LimitReduction> limitReductions, boolean createResultExtension) {

        LfNetwork lfNetwork = loadFlowContext.getNetwork();
        LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElseThrow(); // the contingency can not be null
        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());
        logContingency(lfNetwork, lfContingency);

        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);

        // update network result
        var postContingencyNetworkResult = new PostContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension, preContingencyNetworkResult, contingency.getContingency());
        postContingencyNetworkResult.update();

        // detect violations
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, securityAnalysisParameters.getIncreasedViolationsParameters());
        postContingencyLimitViolationManager.detectViolations(lfNetwork);

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

    private OperatorStrategyResult computeOperatorStrategyResult(OperatorStrategy operatorStrategy,
            DcLoadFlowContext loadFlowContext, SecurityAnalysisParameters securityAnalysisParameters,
            List<LfAction> operatorStrategyLfActions, PropagatedContingency contingency, double[] postContingencyStates,
            LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
           List<LimitReduction> limitReductions, boolean createResultExtension) {
        LfNetwork lfNetwork = loadFlowContext.getNetwork();
//        LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElseThrow(); // the contingency can not be null
//        LOGGER.info("Start operator strategy {} after contingency '{}' simulation on network {}", operatorStrategy.getId(),
//                operatorStrategy.getContingencyContext().getContingencyId(), lfNetwork);

//        LfAction.apply(operatorStrategyLfActions, lfNetwork, lfContingency, loadFlowContext.getParameters().getNetworkParameters());

        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);

        // restart LF on post contingency and post actions equation system
        var postActionsViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, securityAnalysisParameters.getIncreasedViolationsParameters());
        var postActionsNetworkResult = new PreContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension);

        // update network result
        postActionsNetworkResult.update();

        // detect violations
        postActionsViolationManager.detectViolations(lfNetwork);

//        LOGGER.info("Operator strategy {} after contingency '{}' simulation done on network {} in {} ms", operatorStrategy.getId(),
//                operatorStrategy.getContingencyContext().getContingencyId(), lfNetwork, stopwatch.elapsed(TimeUnit.MILLISECONDS));

        return new OperatorStrategyResult(operatorStrategy, PostContingencyComputationStatus.CONVERGED,
                new LimitViolationsResult(postActionsViolationManager.getLimitViolations()),
                new NetworkResult(postActionsNetworkResult.getBranchResults(),
                        postActionsNetworkResult.getBusResults(),
                        postActionsNetworkResult.getThreeWindingsTransformerResults()));
    }
    @Override
    protected SecurityAnalysisResult runSimulations(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies, DcLoadFlowParameters dcParameters,
                                                    SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                    List<Action> actions, List<LimitReduction> limitReductions) {
        Map<String, Action> actionsById = indexActionsById(actions);
        Set<Action> neededActions = new HashSet<>(actionsById.size());
        Map<String, List<OperatorStrategy>> operatorStrategiesByContingencyId = indexOperatorStrategiesByContingencyId(propagatedContingencies, operatorStrategies, actionsById, neededActions);
        Map<String, LfAction> lfActionById = createLfActions(lfNetwork, neededActions, network, dcParameters.getNetworkParameters()); // only convert needed actions

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        boolean createResultExtension = openSecurityAnalysisParameters.isCreateResultExtension();

        // Override parameters to use Woodbury engine
        dcParameters.getEquationSystemCreationParameters().setForcePhaseControlOffAndAddAngle1Var(true); // Needed to force at 0 a PST shifting angle when a PST is lost
        dcParameters.getNetworkParameters().setMinImpedance(true); // Needed because Woodbury does not handle zero impedance lines
        try (DcLoadFlowContext context = createLoadFlowContext(lfNetwork, dcParameters)) {
            ReportNode networkReportNode = lfNetwork.getReportNode();
            ReportNode preContSimReportNode = Reports.createPreContingencySimulation(networkReportNode);
            lfNetwork.setReportNode(preContSimReportNode);

            // prepare contingencies for connectivity analysis and Woodbury engine
            filterPropagatedContingencies(lfNetwork, propagatedContingencies);
            filterActions(actions);
            cleanContingencies(lfNetwork, propagatedContingencies);

            double[] preContingencyStates = DcSensitivityAnalysis.runDcLoadFlow(context, new DisabledNetwork(), reportNode);

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
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();
            LOGGER.info("Processing post contingency results for contingencies with no connectivity break");
            connectivityBreakAnalysisResults.nonBreakingConnectivityContingencies()
                    .forEach(contingency -> {
                        // only process contingencies that impact the network
                        if (!contingency.hasNoImpact()) {
                            double[] postContingencyStates = calculatePostContingencyStatesForAContingency(context, connectivityBreakAnalysisResults.contingenciesStates(), preContingencyStates, contingency,
                                    connectivityBreakAnalysisResults.contingencyElementByBranch(), Collections.emptySet(), Collections.emptySet(), reportNode,
                                    Collections.emptySet(), List.of(), new DenseMatrix(0, 0));
                            PostContingencyResult postContingencyResult = computePostContingencyResult(context, securityAnalysisParameters, preContingencyLimitViolationManager,
                                    preContingencyNetworkResult, contingency, postContingencyStates, limitReductions, createResultExtension);
                            postContingencyResults.add(postContingencyResult);

                            networkState.restore();

                            // TODO : remove, just to test something
                            List<OperatorStrategy> operatorStrategiesForThisContingency = operatorStrategiesByContingencyId.get(contingency.getContingency().getId()); // TODO : check if the ID is the same here
                            for (OperatorStrategy operatorStrategy : operatorStrategiesForThisContingency) {
//                                lfNetwork.setGeneratorsInitialTargetPToTargetP();
//                                List<String> actionIds = checkCondition(operatorStrategy, postContingencyResult.getLimitViolationsResult());
//                                List<LfAction> operatorStrategyLfActions = actionIds.stream()
//                                        .map(lfActionById::get)
//                                        .filter(Objects::nonNull)
//                                        .toList();
//                                List<ComputedActionElement> actionElements =
//                                        operatorStrategyLfActions.stream()
//                                                .map(action -> new ComputedActionElement(action, lfNetwork, context.getEquationSystem()))
//                                                .filter(element -> element.getLfBranchEquation() != null)
//                                                .collect(Collectors.toList());
//                                ComputedActionElement.setContingencyIndexes(actionElements);
//                                NetworkState networkState = NetworkState.save(lfNetwork);
//                                lfActions.forEach(action -> action.apply(loadFlowContext.getParameters().getNetworkParameters()));
//                                DenseMatrix actionsStates = calculateActionsStates(context, actionElements);
//                                networkState.restore();
                                List<String> actionIds = checkCondition(operatorStrategy, postContingencyResult.getLimitViolationsResult());
                                List<LfAction> operatorStrategyLfActions = actionIds.stream()
                                        .map(lfActionById::get)
                                        .filter(Objects::nonNull)
                                        .toList();
                                List<ComputedActionElement> actionElements =
                                        operatorStrategyLfActions.stream()
                                                .map(action -> new ComputedActionElement(action, lfNetwork, context.getEquationSystem()))
                                                .filter(element -> element.getLfBranchEquation() != null)
                                                .collect(Collectors.toList());
                                ComputedActionElement.setActionIndexes(actionElements);
                                DenseMatrix actionsStates = calculateActionsStates(context, actionElements);
                                double[] postContingencyAndActionsStates = calculatePostContingencyStatesForAContingency(context, connectivityBreakAnalysisResults.contingenciesStates(), preContingencyStates, contingency,
                                        connectivityBreakAnalysisResults.contingencyElementByBranch(), Collections.emptySet(), Collections.emptySet(), reportNode,
                                        Collections.emptySet(), operatorStrategyLfActions, actionsStates);
                                OperatorStrategyResult operatorStrategyResult = computeOperatorStrategyResult(operatorStrategy, context, securityAnalysisParameters,
                                        operatorStrategyLfActions, contingency, postContingencyAndActionsStates, preContingencyLimitViolationManager, preContingencyNetworkResult,
                                        limitReductions, createResultExtension);
                                operatorStrategyResults.add(operatorStrategyResult);
                                networkState.restore();
                            }

                        }
                    });

            LOGGER.info("Processing post contingency results for contingencies breaking connectivity");
            connectivityBreakAnalysisResults.connectivityAnalysisResults()
                    .forEach(connectivityAnalysisResult -> {
                        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();

                        // only process contingencies that impact the network
                        if (!contingency.hasNoImpact()) {
                            double[] postContingencyStates = calculatePostContingencyStatesForAContingencyBreakingConnectivity(connectivityAnalysisResult, context,
                                    connectivityBreakAnalysisResults.contingencyElementByBranch(), preContingencyStates,
                                    connectivityBreakAnalysisResults.contingenciesStates(), reportNode);
                            PostContingencyResult postContingencyResult = computePostContingencyResult(context, securityAnalysisParameters, preContingencyLimitViolationManager,
                                    preContingencyNetworkResult, contingency, postContingencyStates, limitReductions, createResultExtension);
                            postContingencyResults.add(postContingencyResult);
                            networkState.restore();
                        }
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