/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.google.common.collect.Lists;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.lf.LoadFlowEngine;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkLoader;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DcLoadFlowEngine implements LoadFlowEngine<DcVariableType, DcEquationType, DcLoadFlowParameters, DcLoadFlowResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DcLoadFlowEngine.class);

    private final DcLoadFlowContext context;

    public DcLoadFlowEngine(DcLoadFlowContext context) {
        this.context = Objects.requireNonNull(context);
    }

    private static class RunningContext {

        private boolean lastSolverSuccess;

        private int solverTotalExecutions = 0;

        private int outerLoopTotalIterations = 0;

        private OuterLoopResult lastOuterLoopResult = OuterLoopResult.stable();
    }

    @Override
    public DcLoadFlowContext getContext() {
        return context;
    }

    public static double distributeSlack(LfNetwork network, Collection<LfBus> buses, LoadFlowParameters.BalanceType balanceType, boolean useActiveLimits) {
        double mismatch = getActivePowerMismatch(buses);
        ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(balanceType, false, useActiveLimits);
        var result = activePowerDistribution.run(network.getReferenceGenerator(), buses, mismatch);
        return mismatch - result.remainingMismatch();
    }

    public static double getActivePowerMismatch(Collection<LfBus> buses) {
        double mismatch = 0;
        for (LfBus b : buses) {
            mismatch += b.getGenerationTargetP() - b.getLoadTargetP();
        }
        return -mismatch;
    }

    public static void initStateVector(LfNetwork network, EquationSystem<DcVariableType, DcEquationType> equationSystem, VoltageInitializer initializer) {
        double[] x = new double[equationSystem.getIndex().getSortedVariablesToFind().size()];
        for (Variable<DcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            switch (v.getType()) {
                case BUS_PHI:
                    x[v.getRow()] = initializer.getAngle(network.getBus(v.getElementNum()));
                    break;

                case BRANCH_ALPHA1:
                    x[v.getRow()] = network.getBranch(v.getElementNum()).getPiModel().getA1();
                    break;

                case DUMMY_P:
                    x[v.getRow()] = 0;
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type " + v.getType());
            }
        }
        equationSystem.getStateVector().set(x);
    }

    public static void updateNetwork(LfNetwork network, EquationSystem<DcVariableType, DcEquationType> equationSystem, double[] x) {
        // update state variable
        for (Variable<DcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            switch (v.getType()) {
                case BUS_PHI:
                    network.getBus(v.getElementNum()).setAngle(x[v.getRow()]);
                    break;

                case BRANCH_ALPHA1:
                    network.getBranch(v.getElementNum()).getPiModel().setA1(x[v.getRow()]);
                    break;

                case DUMMY_P:
                    // nothing to do
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type " + v.getType());
            }
        }
    }

    private void runOuterLoop(DcOuterLoop outerLoop, DcOuterLoopContext outerLoopContext, RunningContext runningContext) {
        ReportNode olReportNode = Reports.createOuterLoopReporter(outerLoopContext.getNetwork().getReportNode(), outerLoop.getName());
        OuterLoopResult outerLoopResult;
        int outerLoopIteration = 0;

        // re-run linear system solving until stabilization
        do {
            // check outer loop status
            outerLoopContext.setIteration(outerLoopIteration);
            outerLoopContext.setLoadFlowContext(context);
            outerLoopContext.setOuterLoopTotalIterations(runningContext.outerLoopTotalIterations);
            outerLoopResult = outerLoop.check(outerLoopContext, olReportNode);
            runningContext.lastOuterLoopResult = outerLoopResult;

            if (outerLoopResult.status() == OuterLoopStatus.UNSTABLE) {
                LOGGER.debug("Start outer loop '{}' iteration {}", outerLoop.getName(), outerLoopIteration);

                // if not yet stable, restart linear system solving
                double[] targetVectorArray = context.getTargetVector().getArray().clone();
                runningContext.lastSolverSuccess = solve(targetVectorArray, context.getJacobianMatrix(), olReportNode);
                runningContext.solverTotalExecutions++;

                if (runningContext.lastSolverSuccess) {
                    context.getEquationSystem().getStateVector().set(targetVectorArray);
                    updateNetwork(outerLoopContext.getNetwork(), context.getEquationSystem(), targetVectorArray);
                }

                outerLoopIteration++;
                runningContext.outerLoopTotalIterations++;
            }
        } while (outerLoopResult.status() == OuterLoopStatus.UNSTABLE
                && runningContext.lastSolverSuccess
                && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());

        if (outerLoopResult.status() != OuterLoopStatus.STABLE) {
            Reports.reportUnsuccessfulOuterLoop(olReportNode, outerLoopResult.status().name());
        }
    }

    public static boolean solve(double[] targetVectorArray,
                                JacobianMatrix<DcVariableType, DcEquationType> jacobianMatrix,
                                ReportNode reportNode) {
        try {
            jacobianMatrix.solveTransposed(targetVectorArray);
            return true;
        } catch (MatrixException e) {
            Reports.reportDcLfSolverFailure(reportNode, e.getMessage());
            LOGGER.error("Failed to solve linear system for DC load flow", e);
            return false;
        }
    }

    public DcLoadFlowResult run() {
        LfNetwork network = context.getNetwork();
        ReportNode reportNode = network.getReportNode();
        EquationSystem<DcVariableType, DcEquationType> equationSystem = context.getEquationSystem();
        DcLoadFlowParameters parameters = context.getParameters();
        TargetVector<DcVariableType, DcEquationType> targetVector = context.getTargetVector();
        RunningContext runningContext = new RunningContext();
        List<DcOuterLoop> outerLoops = parameters.getOuterLoops();

        List<Pair<DcOuterLoop, DcOuterLoopContext>> outerLoopsAndContexts = outerLoops.stream()
                .map(outerLoop -> Pair.of(outerLoop, new DcOuterLoopContext(network)))
                .toList();

        // outer loops initialization
        for (var outerLoopAndContext : outerLoopsAndContexts) {
            var outerLoop = outerLoopAndContext.getLeft();
            var outerLoopContext = outerLoopAndContext.getRight();
            outerLoop.initialize(outerLoopContext);
        }

        initStateVector(network, equationSystem, new UniformValueVoltageInitializer());

        double initialSlackBusActivePowerMismatch = getActivePowerMismatch(network.getBuses());
        double distributedActivePower = 0.0;

        boolean isAreaInterchangeControl = outerLoops.stream().anyMatch(DcAreaInterchangeControlOuterLoop.class::isInstance);
        if (parameters.isDistributedSlack() || isAreaInterchangeControl) {
            LoadFlowParameters.BalanceType balanceType = parameters.getBalanceType();
            boolean useActiveLimits = parameters.getNetworkParameters().isUseActiveLimits();
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(balanceType, false, useActiveLimits);
            var result = activePowerDistribution.run(network, initialSlackBusActivePowerMismatch);
            final LfGenerator referenceGenerator;
            final OpenLoadFlowParameters.SlackDistributionFailureBehavior behavior;
            if (isAreaInterchangeControl && network.hasArea()) {
                // actual behavior will be handled by the outerloop itself, just leave on slack bus here
                behavior = OpenLoadFlowParameters.SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS;
                referenceGenerator = null;
            } else {
                behavior = parameters.getSlackDistributionFailureBehavior();
                referenceGenerator = context.getNetwork().getReferenceGenerator();
            }
            ActivePowerDistribution.ResultWithFailureBehaviorHandling resultWbh = ActivePowerDistribution.handleDistributionFailureBehavior(
                    behavior,
                    referenceGenerator,
                    initialSlackBusActivePowerMismatch,
                    result,
                    "Failed to distribute slack bus active power mismatch, %.2f MW remains"
            );
            double remainingMismatch = resultWbh.remainingMismatch();
            distributedActivePower = initialSlackBusActivePowerMismatch - remainingMismatch;
            if (Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
                Reports.reportMismatchDistributionFailure(reportNode, remainingMismatch * PerUnit.SB);
            } else {
                ActivePowerDistribution.reportAndLogSuccess(reportNode, initialSlackBusActivePowerMismatch, resultWbh);
            }
            if (resultWbh.failed()) {
                distributedActivePower -= resultWbh.failedDistributedActivePower();
                runningContext.lastSolverSuccess = false;
                runningContext.lastOuterLoopResult = new OuterLoopResult("DistributedSlack", OuterLoopStatus.FAILED, resultWbh.failedMessage());
                Reports.reportDcLfComplete(reportNode, runningContext.lastSolverSuccess, runningContext.lastOuterLoopResult.status().name());
                return buildDcLoadFlowResult(network, runningContext, initialSlackBusActivePowerMismatch, distributedActivePower);
            }
        }

        // we need to copy the target array because JacobianMatrix.solveTransposed take as an input the second member
        // and reuse the array to fill with the solution
        // so we need to copy to later the target as it is and reusable for next run
        var targetVectorArray = targetVector.getArray().clone();

        // First linear system solution
        runningContext.lastSolverSuccess = solve(targetVectorArray, context.getJacobianMatrix(), reportNode);
        equationSystem.getStateVector().set(targetVectorArray);
        updateNetwork(network, equationSystem, targetVectorArray);

        // continue with outer loops only if solver succeed
        if (runningContext.lastSolverSuccess) {
            int oldSolverTotalExecutions;
            do {
                oldSolverTotalExecutions = runningContext.solverTotalExecutions;
                // outer loops are nested: innermost loop first in the list, outermost loop last
                for (var outerLoopAndContext : outerLoopsAndContexts) {
                    runOuterLoop(outerLoopAndContext.getLeft(), outerLoopAndContext.getRight(), runningContext);

                    // continue with next outer loop only if:
                    // - last solver run succeed,
                    // - last OuterLoopStatus is not FAILED
                    // - we have not reached max number of outer loop iteration
                    if (!runningContext.lastSolverSuccess
                            || runningContext.lastOuterLoopResult.status() == OuterLoopStatus.FAILED
                            || runningContext.outerLoopTotalIterations >= context.getParameters().getMaxOuterLoopIterations()) {
                        break;
                    }
                }
            } while (runningContext.solverTotalExecutions > oldSolverTotalExecutions
                    && runningContext.lastSolverSuccess
                    && runningContext.lastOuterLoopResult.status() != OuterLoopStatus.FAILED
                    && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());
        }

        // outer loops finalization (in reverse order to allow correct cleanup)
        for (var outerLoopAndContext : Lists.reverse(outerLoopsAndContexts)) {
            var outerLoop = outerLoopAndContext.getLeft();
            var outerLoopContext = outerLoopAndContext.getRight();
            if (outerLoop instanceof DcAreaInterchangeControlOuterLoop activePowerDistributionOuterLoop) {
                distributedActivePower += activePowerDistributionOuterLoop.getDistributedActivePower(outerLoopContext);
            }
            outerLoop.cleanup(outerLoopContext);
        }

        // set all calculated voltages to NaN
        if (parameters.isSetVToNan()) {
            for (LfBus bus : network.getBuses()) {
                bus.setV(Double.NaN);
            }
        }

        Reports.reportDcLfComplete(reportNode, runningContext.lastSolverSuccess, runningContext.lastOuterLoopResult.status().name());

        return buildDcLoadFlowResult(network, runningContext, initialSlackBusActivePowerMismatch, distributedActivePower);
    }

    DcLoadFlowResult buildDcLoadFlowResult(LfNetwork network, RunningContext runningContext, double initialSlackBusActivePowerMismatch, double finalDistributedActivePower) {
        double slackBusActivePowerMismatch;
        double distributedActivePower;
        if (runningContext.lastSolverSuccess && runningContext.lastOuterLoopResult.status() == OuterLoopStatus.STABLE) {
            slackBusActivePowerMismatch = getActivePowerMismatch(network.getBuses());
            distributedActivePower = finalDistributedActivePower;
        } else {
            slackBusActivePowerMismatch = initialSlackBusActivePowerMismatch;
            distributedActivePower = 0.0;
        }
        DcLoadFlowResult result = new DcLoadFlowResult(network, runningContext.outerLoopTotalIterations, runningContext.lastSolverSuccess, runningContext.lastOuterLoopResult, slackBusActivePowerMismatch, distributedActivePower);
        LOGGER.info("DC loadflow complete on network {} (result={})", context.getNetwork(), result);
        return result;
    }

    public static <T> List<DcLoadFlowResult> run(T network, LfNetworkLoader<T> networkLoader, DcLoadFlowParameters parameters, ReportNode reportNode) {
        return LfNetwork.load(network, networkLoader, parameters.getNetworkParameters(), reportNode)
                .stream()
                .map(n -> {
                    if (n.getValidity() == LfNetwork.Validity.VALID) {
                        try (DcLoadFlowContext context = new DcLoadFlowContext(n, parameters)) {
                            return new DcLoadFlowEngine(context)
                                    .run();
                        }
                    }

                    return DcLoadFlowResult.createNoCalculationResult(n);
                })
                .toList();
    }

}
