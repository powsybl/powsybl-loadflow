/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.google.common.collect.Lists;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.ac.outerloop.AreaInterchangeControlOuterloop;
import com.powsybl.openloadflow.ac.outerloop.DistributedSlackOuterLoop;
import com.powsybl.openloadflow.ac.solver.*;
import com.powsybl.openloadflow.lf.LoadFlowEngine;
import com.powsybl.openloadflow.lf.outerloop.DistributedSlackContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcloadFlowEngine implements LoadFlowEngine<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcloadFlowEngine.class);

    private final AcLoadFlowContext context;

    private final AcSolverFactory solverFactory;

    public AcloadFlowEngine(AcLoadFlowContext context) {
        this(context, new NewtonRaphsonFactory());
    }

    public AcloadFlowEngine(AcLoadFlowContext context, AcSolverFactory solverFactory) {
        this.context = Objects.requireNonNull(context);
        this.solverFactory = Objects.requireNonNull(solverFactory);
    }

    @Override
    public AcLoadFlowContext getContext() {
        return context;
    }

    private static class RunningContext {

        private AcSolverResult lastSolverResult;

        private final Map<String, MutableInt> outerLoopIterationByType = new HashMap<>();

        private int outerLoopTotalIterations = 0;

        private final MutableInt nrTotalIterations = new MutableInt();

        private OuterLoopResult lastOuterLoopResult = OuterLoopResult.stable();
    }

    private void runOuterLoop(AcOuterLoop outerLoop, AcOuterLoopContext outerLoopContext, AcSolver solver, RunningContext runningContext) {
        ReportNode olReportNode = Reports.createOuterLoopReporter(outerLoopContext.getNetwork().getReportNode(), outerLoop.getName());

        // for each outer loop re-run solver until stabilization
        OuterLoopResult outerLoopResult;
        do {
            MutableInt outerLoopIteration = runningContext.outerLoopIterationByType.computeIfAbsent(outerLoop.getName(), k -> new MutableInt());

            // check outer loop status
            outerLoopContext.setIteration(outerLoopIteration.getValue());
            outerLoopContext.setOuterLoopTotalIterations(runningContext.outerLoopTotalIterations);
            outerLoopContext.setLastSolverResult(runningContext.lastSolverResult);
            outerLoopContext.setLoadFlowContext(context);
            outerLoopResult = outerLoop.check(outerLoopContext, olReportNode);
            runningContext.lastOuterLoopResult = outerLoopResult;

            if (outerLoopResult.status() == OuterLoopStatus.UNSTABLE) {
                LOGGER.debug("Start outer loop '{}' iteration {}", outerLoop.getName(), runningContext.outerLoopTotalIterations);

                ReportNode reportNode = context.getNetwork().getReportNode();
                if (context.getParameters().isDetailedReport()) {
                    reportNode = Reports.createDetailedSolverReporterOuterLoop(reportNode,
                            solver.getName(),
                            context.getNetwork().getNumCC(),
                            context.getNetwork().getNumSC(),
                            runningContext.outerLoopTotalIterations + 1,
                            outerLoop.getName());
                }

                // if not yet stable, restart solver
                runningContext.lastSolverResult = solver.run(new PreviousValueVoltageInitializer(), reportNode);

                runningContext.nrTotalIterations.add(runningContext.lastSolverResult.getIterations());
                runningContext.outerLoopTotalIterations++;

                outerLoopIteration.increment();
            }
        } while (outerLoopResult.status() == OuterLoopStatus.UNSTABLE
                && runningContext.lastSolverResult.getStatus() == AcSolverStatus.CONVERGED
                && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());

        if (outerLoopResult.status() != OuterLoopStatus.STABLE) {
            Reports.reportUnsuccessfulOuterLoop(olReportNode, outerLoopResult.status().name());
        }
    }

    @Override
    public AcLoadFlowResult run() {
        LOGGER.info("Start AC loadflow on network {}", context.getNetwork());

        VoltageInitializer voltageInitializer = context.getParameters().getVoltageInitializer();
        // in case of a DC voltage initializer, an DC equation system in created and equations are attached
        // to the network. It is important that DC init is done before AC equation system is created by
        // calling ACLoadContext.getEquationSystem to avoid DC equations overwrite AC ones in the network.
        voltageInitializer.prepare(context.getNetwork());

        RunningContext runningContext = new RunningContext();
        double distributedActivePower = 0.0;
        ReportNode reportNode = context.getNetwork().getReportNode();

        // Verify whether a regulated bus voltage exists.
        // If not, then fail immediately with SOLVER_FAILED status.
        // Note that this approach is not perfect and could be improved in the future, in particular in
        // security analysis and remedial actions context.
        // For example: a contingency may cause the last PV node to disappear. In this case here we would
        // just report SOLVER_FAILED. However, there could be other generators blocked at MinQ or MaxQ that
        // _could potentially_ recover the situation, but this will not be tried at all...
        boolean hasVoltageRegulatedBus = context.getNetwork().getBuses().stream()
                .anyMatch(b -> b.isGeneratorVoltageControlEnabled() && !b.isDisabled() && !b.getGeneratorVoltageControl().orElseThrow().isDisabled());
        if (!hasVoltageRegulatedBus) {
            LOGGER.info("Network must have at least one bus with generator voltage control enabled");
            Reports.reportNetworkMustHaveAtLeastOneBusGeneratorVoltageControlEnabled(reportNode);
            runningContext.lastSolverResult = new AcSolverResult(AcSolverStatus.SOLVER_FAILED, 0, Double.NaN);
            return buildAcLoadFlowResult(runningContext, OuterLoopResult.stable(), distributedActivePower);
        }

        AcSolver solver = solverFactory.create(context.getNetwork(),
                                               context.getParameters(),
                                               context.getEquationSystem(),
                                               context.getJacobianMatrix(),
                                               context.getTargetVector(),
                                               context.getEquationVector());

        List<AcOuterLoop> outerLoops = context.getParameters().getOuterLoops();
        List<Pair<AcOuterLoop, AcOuterLoopContext>> outerLoopsAndContexts = outerLoops.stream()
                .map(outerLoop -> Pair.of(outerLoop, new AcOuterLoopContext(context.getNetwork())))
                .toList();

        // outer loops initialization
        for (var outerLoopAndContext : outerLoopsAndContexts) {
            var outerLoop = outerLoopAndContext.getLeft();
            var outerLoopContext = outerLoopAndContext.getRight();
            outerLoop.initialize(outerLoopContext);
        }

        if (context.getParameters().isDetailedReport()) {
            reportNode = Reports.createDetailedSolverReporter(reportNode,
                    solver.getName(),
                    context.getNetwork().getNumCC(),
                    context.getNetwork().getNumSC());
        }
        // initial solver run
        runningContext.lastSolverResult = solver.run(voltageInitializer, reportNode);

        runningContext.nrTotalIterations.add(runningContext.lastSolverResult.getIterations());

        // continue with outer loops only if solver succeed
        if (runningContext.lastSolverResult.getStatus() == AcSolverStatus.CONVERGED) {

            // re-run all outer loops until solver failed or no more solver iterations are needed
            int oldNrTotalIterations;
            do {
                oldNrTotalIterations = runningContext.nrTotalIterations.getValue();

                // outer loops are nested: innermost loop first in the list, outermost loop last
                for (var outerLoopAndContext : outerLoopsAndContexts) {
                    runOuterLoop(outerLoopAndContext.getLeft(), outerLoopAndContext.getRight(), solver, runningContext);

                    // continue with next outer loop only if:
                    // - last solver run succeed,
                    // - last OuterLoopStatus is not FAILED
                    // - we have not reached max number of outer loop iteration
                    if (runningContext.lastSolverResult.getStatus() != AcSolverStatus.CONVERGED
                            || runningContext.lastOuterLoopResult.status() == OuterLoopStatus.FAILED
                            || runningContext.outerLoopTotalIterations >= context.getParameters().getMaxOuterLoopIterations()) {
                        break;
                    }
                }
            } while (runningContext.nrTotalIterations.getValue() > oldNrTotalIterations
                    && runningContext.lastSolverResult.getStatus() == AcSolverStatus.CONVERGED
                    && runningContext.lastOuterLoopResult.status() != OuterLoopStatus.FAILED
                    && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());
        }

        // outer loops finalization (in reverse order to allow correct cleanup)
        for (var outerLoopAndContext : Lists.reverse(outerLoopsAndContexts)) {
            var outerLoop = outerLoopAndContext.getLeft();
            var outerLoopContext = outerLoopAndContext.getRight();
            if (outerLoop instanceof DistributedSlackOuterLoop || outerLoop instanceof AreaInterchangeControlOuterloop) {
                distributedActivePower = ((DistributedSlackContextData) outerLoopContext.getData()).getDistributedActivePower();
            }
            outerLoop.cleanup(outerLoopContext);
        }

        final OuterLoopResult outerLoopFinalResult;
        if (runningContext.lastOuterLoopResult.status() == OuterLoopStatus.FAILED) {
            outerLoopFinalResult = runningContext.lastOuterLoopResult;
        } else {
            outerLoopFinalResult = runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations()
                    ? new OuterLoopResult(runningContext.lastOuterLoopResult.outerLoopName(), OuterLoopStatus.STABLE, runningContext.lastOuterLoopResult.statusText()) :
                    new OuterLoopResult(runningContext.lastOuterLoopResult.outerLoopName(), OuterLoopStatus.UNSTABLE, runningContext.lastOuterLoopResult.statusText());
        }

        return buildAcLoadFlowResult(runningContext, outerLoopFinalResult, distributedActivePower);
    }

    private AcLoadFlowResult buildAcLoadFlowResult(RunningContext runningContext, OuterLoopResult outerLoopFinalResult, double distributedActivePower) {
        AcLoadFlowResult result = new AcLoadFlowResult(context.getNetwork(),
                                                       runningContext.outerLoopTotalIterations,
                                                       runningContext.nrTotalIterations.getValue(),
                                                       runningContext.lastSolverResult.getStatus(),
                                                       outerLoopFinalResult,
                                                       runningContext.lastSolverResult.getSlackBusActivePowerMismatch(),
                                                       distributedActivePower
                                                       );

        LOGGER.info("Ac loadflow complete on network {} (result={})", context.getNetwork(), result);

        Reports.reportAcLfComplete(context.getNetwork().getReportNode(), result.isSuccess(), result.getSolverStatus().name(), result.getOuterLoopResult().status().name());

        context.setResult(result);

        return result;
    }

    public static List<AcLoadFlowResult> run(List<LfNetwork> lfNetworks, AcLoadFlowParameters parameters) {
        return lfNetworks.stream()
                .map(n -> {
                    if (n.getValidity() == LfNetwork.Validity.VALID) {
                        try (AcLoadFlowContext context = new AcLoadFlowContext(n, parameters)) {
                            return new AcloadFlowEngine(context, parameters.getSolverFactory())
                                    .run();
                        }
                    }
                    return AcLoadFlowResult.createNoCalculationResult(n);
                })
                .toList();
    }
}
