/*
 * Copyright (c) 2024-2025, RTE (http://www.rte-france.com)
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
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.*;
import com.powsybl.openloadflow.dc.fastdc.*;
import com.powsybl.openloadflow.dc.fastdc.ConnectivityBreakAnalysis.ConnectivityAnalysisResult;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.action.AbstractLfTapChangerAction;
import com.powsybl.openloadflow.network.action.LfAction;
import com.powsybl.openloadflow.network.action.LfPhaseTapChangerAction;
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
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency, the pre contingency flowStates are overridden.
     * @return the post contingency states for the contingency
     */
    private double[] calculatePostContingencyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                    ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                    ReportNode reportNode) {
        return calculatePostContingencyAndOperatorStrategyStates(loadFlowContext, contingenciesStates, flowStates, connectivityAnalysisResult, contingencyElementByBranch,
                Collections.emptyList(), Collections.emptyMap(), DenseMatrix.EMPTY, reportNode);
    }

    /**
     * Calculate post contingency and post operator strategy states, for a contingency and operator strategy actions
     * If connectivity, a generator, a load or a phase tap changer is lost/modified due to the contingency/operator strategy, the pre contingency flowStates are overridden.
     */
    private double[] calculatePostContingencyAndOperatorStrategyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                                       ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                                       List<LfAction> operatorStrategyLfActions, Map<String, ComputedTapPositionChangeElement> tapPositionChangeElementByBranch,
                                                                       DenseMatrix actionsStates, ReportNode reportNode) {
        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();

        // reset active flow of hvdc line without power
        connectivityAnalysisResult.getHvdcsWithoutPower().forEach(hvdcWithoutPower -> {
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation1().getId());
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation2().getId());
        });

        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !connectivityAnalysisResult.getElementsToReconnect().contains(element))
                .map(contingencyElementByBranch::get)
                .collect(Collectors.toList());
        List<ComputedTapPositionChangeElement> actionElements = operatorStrategyLfActions.stream()
                .filter(AbstractLfTapChangerAction.class::isInstance)
                .map(lfAction -> ((AbstractLfTapChangerAction<?>) lfAction).getChange().getBranch().getId())
                .map(tapPositionChangeElementByBranch::get)
                .toList();

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(connectivityAnalysisResult.getPartialDisabledBranches());
        DisabledNetwork disabledNetwork = new DisabledNetwork(connectivityAnalysisResult.getDisabledBuses(), disabledBranches);

        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates, actionElements, actionsStates);
        double[] newFlowStates = flowStates;
        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToLose().isEmpty()) {

            // get the lost phase tap changers for this contingency
            Set<LfBranch> lostPhaseControllers = contingency.getBranchIdsToOpen().keySet().stream()
                    .filter(element -> !connectivityAnalysisResult.getElementsToReconnect().contains(element))
                    .map(contingencyElementByBranch::get)
                    .map(ComputedContingencyElement::getLfBranch)
                    .filter(LfBranch::hasPhaseControllerCapability)
                    .collect(Collectors.toSet());

            // if a phase tap changer is lost or if the connectivity have changed, we must recompute load flows
            // same if there is an action, as they are only on pst for now
            if (!connectivityAnalysisResult.getDisabledBuses().isEmpty() || !lostPhaseControllers.isEmpty() || !operatorStrategyLfActions.isEmpty()) {
                newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, reportNode, operatorStrategyLfActions);
            }
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
        } else {
            // if contingency includes the loss of a DC line or a generator or a load
            // save bus dc base state for later restoration
            List<BusDcState> busStates = ElementState.save(lfNetwork.getBuses(), BusDcState::save);
            connectivityAnalysisResult.toLfContingency()
                    .ifPresent(lfContingency -> lfContingency.applyOnGeneratorsLoadsHvdcs(loadFlowContext.getParameters().getBalanceType(), false));
            newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, reportNode, operatorStrategyLfActions);
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
            ElementState.restore(busStates);
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

    static class NetworkDcRestrictiveLimits {
        final double[] mostRestrictiveCurrentLimit;
        final double[] mostRestrictiveActivePowerLimit;

        public NetworkDcRestrictiveLimits(LfNetwork lfNetwork, List<LimitReduction> limitReductions) {
            Objects.requireNonNull(lfNetwork);
            Objects.requireNonNull(limitReductions);
            mostRestrictiveCurrentLimit = new double[lfNetwork.getBranches().size()];
            Arrays.fill(mostRestrictiveCurrentLimit, Double.MAX_VALUE);
            mostRestrictiveActivePowerLimit = new double[lfNetwork.getBranches().size()];
            Arrays.fill(mostRestrictiveActivePowerLimit, Double.MAX_VALUE);

            LimitReductionManager limitReductionManager = LimitReductionManager.create(limitReductions);
            for (LfBranch lfBranch : lfNetwork.getBranches()) {
                if (lfBranch.getBus1() != null) {
                    for (LfBranch.LfLimit limit : lfBranch.getLimits1(LimitType.CURRENT, limitReductionManager)) {
                        if (limit.getReducedValue() < mostRestrictiveCurrentLimit[lfBranch.getNum()]) {
                            mostRestrictiveCurrentLimit[lfBranch.getNum()] = limit.getReducedValue();
                        }
                    }

                    for (LfBranch.LfLimit limit : lfBranch.getLimits1(LimitType.ACTIVE_POWER, limitReductionManager)) {
                        if (limit.getReducedValue() < mostRestrictiveActivePowerLimit[lfBranch.getNum()]) {
                            mostRestrictiveActivePowerLimit[lfBranch.getNum()] = limit.getReducedValue();
                        }
                    }
                }

                if (lfBranch.getBus2() != null) {
                    for (LfBranch.LfLimit limit : lfBranch.getLimits2(LimitType.CURRENT, limitReductionManager)) {
                        if (limit.getReducedValue() < mostRestrictiveCurrentLimit[lfBranch.getNum()]) {
                            mostRestrictiveCurrentLimit[lfBranch.getNum()] = limit.getReducedValue();
                        }
                    }

                    for (LfBranch.LfLimit limit : lfBranch.getLimits2(LimitType.ACTIVE_POWER, limitReductionManager)) {
                        if (limit.getReducedValue() < mostRestrictiveActivePowerLimit[lfBranch.getNum()]) {
                            mostRestrictiveActivePowerLimit[lfBranch.getNum()] = limit.getReducedValue();
                        }
                    }
                }
            }
        }
    }

    static class NetworkDcVectorState {
        final boolean[] disabled;
        final double[] p1;
        final double[] i1;
        final double[] p2;
        final double[] i2;

        public NetworkDcVectorState(double[] postContingencyStates, LfNetwork lfNetwork, LfContingency lfContingency, DcEquationSystemCreationParameters parameters) {
            this(postContingencyStates, lfNetwork, lfContingency, Collections.emptyMap(), parameters);
        }

        public NetworkDcVectorState(double[] postContingencyAndActionsStates, LfNetwork lfNetwork, LfContingency lfContingency,
                                    Map<String, LfPhaseTapChangerAction> pstActionsByBranch, DcEquationSystemCreationParameters parameters) {
            Objects.requireNonNull(postContingencyAndActionsStates);
            Objects.requireNonNull(lfNetwork);
            Objects.requireNonNull(pstActionsByBranch);
            Objects.requireNonNull(parameters);
            disabled = new boolean[lfNetwork.getBranches().size()];
            p1 = new double[lfNetwork.getBranches().size()];
            i1 = new double[lfNetwork.getBranches().size()];
            p2 = new double[lfNetwork.getBranches().size()];
            i2 = new double[lfNetwork.getBranches().size()];
            StateVector sv = new StateVector(postContingencyAndActionsStates);
            Arrays.fill(disabled, false);
            if (lfContingency != null) {
                for (LfBranch disabledBranch : lfContingency.getDisabledNetwork().getBranches()) {
                    disabled[disabledBranch.getNum()] = true;
                }
            }
            double dcPowerFactor = parameters.getDcPowerFactor();
            for (LfBranch branch : lfNetwork.getBranches()) {
                PiModel piModel = pstActionsByBranch.containsKey(branch.getId()) ? pstActionsByBranch.get(branch.getId()).getChange().getNewPiModel()
                        : branch.getPiModel();
                p1[branch.getNum()] = branch.getP1() instanceof ClosedBranchSide1DcFlowEquationTerm ? ((ClosedBranchSide1DcFlowEquationTerm) branch.getP1()).eval(sv, piModel) : Double.NaN;
                i1[branch.getNum()] = Math.abs(p1[branch.getNum()]) / dcPowerFactor;
                p2[branch.getNum()] = branch.getP2() instanceof ClosedBranchSide2DcFlowEquationTerm ? ((ClosedBranchSide2DcFlowEquationTerm) branch.getP2()).eval(sv, piModel) : Double.NaN;
                i2[branch.getNum()] = Math.abs(p2[branch.getNum()]) / dcPowerFactor;
            }
        }

        void detectBranchViolations(LimitViolationManager limitViolationManager, LfNetwork lfNetwork, NetworkDcRestrictiveLimits dcRestrictiveLimits) {
            limitViolationManager.detectBranchesViolations(lfNetwork,
                    branch -> disabled[branch.getNum()],
                    branch -> dcRestrictiveLimits.mostRestrictiveCurrentLimit[branch.getNum()] < i1[branch.getNum()]
                            || dcRestrictiveLimits.mostRestrictiveCurrentLimit[branch.getNum()] < i2[branch.getNum()],
                    branch -> dcRestrictiveLimits.mostRestrictiveActivePowerLimit[branch.getNum()] < p1[branch.getNum()]
                            || dcRestrictiveLimits.mostRestrictiveActivePowerLimit[branch.getNum()] < p2[branch.getNum()],
                    branch -> i1[branch.getNum()],
                    branch -> p1[branch.getNum()],
                    branch -> {
                        throw new PowsyblException("s1 useless");
                    },
                    branch -> i2[branch.getNum()],
                    branch -> p2[branch.getNum()],
                    branch -> {
                        throw new PowsyblException("s2 useless");
                    });
        }

        AbstractNetworkResult.BranchResultCreator createBranchResultCreator() {
            return (branch, preContingencyBranchP1, preContingencyBranchOfContingencyP1, createExtension) -> {
                int num = branch.getNum();
                double flowTransfer = Double.NaN;
                if (!Double.isNaN(preContingencyBranchP1) && !Double.isNaN(preContingencyBranchOfContingencyP1)) {
                    flowTransfer = (p1[num] * PerUnit.SB - preContingencyBranchP1) / preContingencyBranchOfContingencyP1;
                }
                double currentScale1 = PerUnit.ib(branch.getBus1().getNominalV()); // FIXME : what happens if the branch is opened ?
                double currentScale2 = PerUnit.ib(branch.getBus2().getNominalV());
                var branchResult = new BranchResult(branch.getId(), p1[num] * PerUnit.SB, Double.NaN, currentScale1 * i1[num],
                        p2[num] * PerUnit.SB, Double.NaN, currentScale2 * i2[num], flowTransfer);
                return List.of(branchResult);
            };
        }
    }

    /**
     * Returns the post contingency result associated to given contingency and post contingency states.
     */
    private PostContingencyResult computePostContingencyResultFromPostContingencyStates(DcLoadFlowContext loadFlowContext, Contingency contingency, LfContingency lfContingency,
                                                                                        LimitViolationManager preContingencyLimitViolationManager, PreContingencyNetworkResult preContingencyNetworkResult,
                                                                                        boolean createResultExtension, SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters,
                                                                                        double[] postContingencyStates, List<LimitReduction> limitReductions, NetworkDcRestrictiveLimits dcRestrictiveLimits) {

        // update network state with post contingency states
        LfNetwork lfNetwork = loadFlowContext.getNetwork();

        NetworkDcVectorState dcVecState = new NetworkDcVectorState(postContingencyStates, lfNetwork, lfContingency, loadFlowContext.getParameters().getEquationSystemCreationParameters());

        // update post contingency network result
        var postContingencyNetworkResult = new PostContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension,
                dcVecState.createBranchResultCreator(), preContingencyNetworkResult, contingency);
        postContingencyNetworkResult.update(lfBranch -> dcVecState.disabled[lfBranch.getNum()]);

        // detect violations
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, violationsParameters);
        dcVecState.detectBranchViolations(postContingencyLimitViolationManager, lfNetwork, dcRestrictiveLimits);

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
                                                                                                             double[] postContingencyAndOperatorStrategyStates, List<LimitReduction> limitReductions, NetworkDcRestrictiveLimits dcRestrictiveLimits) {
        // update network state with post contingency and post operator strategy states
        LfNetwork lfNetwork = loadFlowContext.getNetwork();

        // get the pst actions by branch id
        Map<String, LfPhaseTapChangerAction> pstActionsByBranchId = operatorStrategyLfActions.stream()
                .collect(Collectors.toMap(
                        action -> ((LfPhaseTapChangerAction) action).getChange().getBranch().getId(),
                        action -> (LfPhaseTapChangerAction) action
                ));

        NetworkDcVectorState dcVecState = new NetworkDcVectorState(postContingencyAndOperatorStrategyStates, lfNetwork, lfContingency,
                pstActionsByBranchId, loadFlowContext.getParameters().getEquationSystemCreationParameters());

        // update network result
        var postActionsNetworkResult = new PreContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension,
                dcVecState.createBranchResultCreator());
        postActionsNetworkResult.update(lfBranch -> dcVecState.disabled[lfBranch.getNum()]);

        // detect violations
        var postActionsLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, violationsParameters);
        dcVecState.detectBranchViolations(postActionsLimitViolationManager, lfNetwork, dcRestrictiveLimits);

        return new OperatorStrategyResult(operatorStrategy, PostContingencyComputationStatus.CONVERGED,
                new LimitViolationsResult(postActionsLimitViolationManager.getLimitViolations()),
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
                                                              NetworkDcRestrictiveLimits dcRestrictiveLimits, List<PostContingencyResult> postContingencyResults, List<OperatorStrategyResult> operatorStrategyResults) {
        // process results only if contingency impacts the network
        connectivityAnalysisResult.toLfContingency().ifPresent(lfContingency -> {
            LfNetwork lfNetwork = context.getNetwork();

            Contingency contingency = connectivityAnalysisResult.getPropagatedContingency().getContingency();
            ReportNode postContSimReportNode = Reports.createPostContingencySimulation(lfNetwork.getReportNode(), contingency.getId());
            lfNetwork.setReportNode(postContSimReportNode);

            // process post contingency result
            double[] postContingencyStates = toPostContingencyStates.apply(connectivityAnalysisResult);
            PostContingencyResult postContingencyResult = computePostContingencyResultFromPostContingencyStates(context, contingency,
                    lfContingency, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                    violationsParameters, postContingencyStates, limitReductions, dcRestrictiveLimits);
            postContingencyResults.add(postContingencyResult);

            // restore pre contingency states for next calculation
            restorePreContingencyStates.run();

            List<OperatorStrategy> operatorStrategiesForThisContingency = operatorStrategiesByContingencyId.get(contingency.getId());
            if (operatorStrategiesForThisContingency != null) {
                for (OperatorStrategy operatorStrategy : operatorStrategiesForThisContingency) {
                    ReportNode osSimReportNode = Reports.createOperatorStrategySimulation(postContSimReportNode, operatorStrategy.getId());
                    lfNetwork.setReportNode(osSimReportNode);

                    // process operator strategy result
                    // get the actions associated to the operator strategy
                    List<String> actionIds = checkCondition(operatorStrategy, postContingencyResult.getLimitViolationsResult());
                    List<LfAction> operatorStrategyLfActions = actionIds.stream()
                            .map(lfActionById::get)
                            .filter(Objects::nonNull)
                            .toList();

                    double[] postContingencyAndOperatorStrategyStates = toPostContingencyAndOperatorStrategyStates.apply(connectivityAnalysisResult, operatorStrategyLfActions);
                    OperatorStrategyResult operatorStrategyResult = computeOperatorStrategyResultFromPostContingencyAndOperatorStrategyStates(context, lfContingency, operatorStrategy, operatorStrategyLfActions,
                            preContingencyLimitViolationManager, createResultExtension, violationsParameters, postContingencyAndOperatorStrategyStates, limitReductions,
                            dcRestrictiveLimits);
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

            NetworkDcRestrictiveLimits dcRestrictiveLimits = new NetworkDcRestrictiveLimits(lfNetwork, limitReductions);

            NetworkDcVectorState dcVecState = new NetworkDcVectorState(preContingencyStates, lfNetwork, null,
                    Collections.emptyMap(), context.getParameters().getEquationSystemCreationParameters());

            // update network result
            var preContingencyNetworkResult = new PreContingencyNetworkResult(lfNetwork, monitorIndex, createResultExtension,
                    dcVecState.createBranchResultCreator());
            preContingencyNetworkResult.update();

            // detect violations
            var preContingencyLimitViolationManager = new LimitViolationManager(limitReductions);
            dcVecState.detectBranchViolations(preContingencyLimitViolationManager, lfNetwork, dcRestrictiveLimits);

            // compute states with +1 -1 to model the contingencies and run connectivity analysis
            ConnectivityBreakAnalysis.ConnectivityBreakAnalysisResults connectivityBreakAnalysisResults = ConnectivityBreakAnalysis.run(context, propagatedContingencies);

            // compute states with +1 -1 to model the actions in Woodbury engine
            Map<String, ComputedTapPositionChangeElement> tapPositionChangeElementsByBranchId = createTapPositionChangeElementsIndexByBranchId(lfActionById, context.getEquationSystem());
            DenseMatrix actionsStates = ComputedElement.calculateElementsStates(context, tapPositionChangeElementsByBranchId.values());

            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();

            // supplier to compute post contingency states
            // no need to distribute active mismatch due to connectivity modifications
            // this is handled when the slack is distributed in pre contingency states override
            Function<ConnectivityAnalysisResult, double[]> toPostContingencyStates = connectivityAnalysisResult -> calculatePostContingencyStates(context, connectivityBreakAnalysisResults.contingenciesStates(),
                    workingContingencyStates, connectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(), reportNode);
            // function to compute post contingency and post operator strategy states
            BiFunction<ConnectivityAnalysisResult, List<LfAction>, double[]> toPostContingencyAndOperatorStrategyStates = (connectivityAnalysisResult, operatorStrategyLfActions) ->
                    calculatePostContingencyAndOperatorStrategyStates(context, connectivityBreakAnalysisResults.contingenciesStates(), workingContingencyStates,
                            connectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(),
                            operatorStrategyLfActions, tapPositionChangeElementsByBranchId, actionsStates, reportNode);

            LOGGER.info("Processing post contingency results for contingencies with no connectivity break");
            connectivityBreakAnalysisResults.nonBreakingConnectivityContingencies().forEach(nonBreakingConnectivityContingency -> {
                ConnectivityAnalysisResult connectivityAnalysisResult = new ConnectivityAnalysisResult(nonBreakingConnectivityContingency, lfNetwork);
                // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                Runnable restorePreContingencyStates = () -> {
                    // update workingContingencyStates as it may have been updated by post contingency states calculation
                    System.arraycopy(preContingencyStates, 0, workingContingencyStates, 0, preContingencyStates.length);
                };
                addPostContingencyAndOperatorStrategyResults(context, connectivityAnalysisResult, operatorStrategiesByContingencyId, lfActionById, toPostContingencyStates,
                        toPostContingencyAndOperatorStrategyStates, restorePreContingencyStates, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                        securityAnalysisParameters.getIncreasedViolationsParameters(), limitReductions, dcRestrictiveLimits, postContingencyResults, operatorStrategyResults);
            });

            LOGGER.info("Processing post contingency results for contingencies breaking connectivity");
            connectivityBreakAnalysisResults.connectivityAnalysisResults()
                    .forEach(connectivityAnalysisResult -> {
                        // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                        // no need to update workingContingencyStates as an override of flow states will be computed
                        Runnable restorePreContingencyStates = () -> { };
                        addPostContingencyAndOperatorStrategyResults(context, connectivityAnalysisResult, operatorStrategiesByContingencyId, lfActionById, toPostContingencyStates,
                                toPostContingencyAndOperatorStrategyStates, restorePreContingencyStates, preContingencyLimitViolationManager, preContingencyNetworkResult, createResultExtension,
                                securityAnalysisParameters.getIncreasedViolationsParameters(), limitReductions, dcRestrictiveLimits, postContingencyResults, operatorStrategyResults);
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
