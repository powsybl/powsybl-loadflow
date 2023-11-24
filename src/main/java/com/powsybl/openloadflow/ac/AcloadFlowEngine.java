/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.google.common.collect.Lists;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.commons.reporter.TypedValue;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.nr.*;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.ac.outerloop.DistributedSlackOuterLoop;
import com.powsybl.openloadflow.lf.LoadFlowEngine;
import com.powsybl.openloadflow.lf.outerloop.DistributedSlackContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcloadFlowEngine implements LoadFlowEngine<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcloadFlowEngine.class);

    private final AcLoadFlowContext context;

    private final SolverFactory solverFactory;

    public AcloadFlowEngine(AcLoadFlowContext context) {
        this(context, new NewtonRaphsonFactory());
    }

    public AcloadFlowEngine(AcLoadFlowContext context, SolverFactory solverFactory) {
        this.context = Objects.requireNonNull(context);
        this.solverFactory = Objects.requireNonNull(solverFactory);
    }

    @Override
    public AcLoadFlowContext getContext() {
        return context;
    }

    private static class RunningContext {

        private NewtonRaphsonResult lastNrResult;

        private final Map<String, MutableInt> outerLoopIterationByType = new HashMap<>();

        private int outerLoopTotalIterations = 0;

        private final MutableInt nrTotalIterations = new MutableInt();

        private OuterLoopStatus lastOuterLoopStatus;
    }

    private void runOuterLoop(AcOuterLoop outerLoop, AcOuterLoopContext outerLoopContext, Solver solver, RunningContext runningContext) {
        Reporter olReporter = Reports.createOuterLoopReporter(outerLoopContext.getNetwork().getReporter(), outerLoop.getName());

        // for each outer loop re-run Newton-Raphson until stabilization
        OuterLoopStatus outerLoopStatus;
        do {
            MutableInt outerLoopIteration = runningContext.outerLoopIterationByType.computeIfAbsent(outerLoop.getName(), k -> new MutableInt());

            // check outer loop status
            outerLoopContext.setIteration(outerLoopIteration.getValue());
            outerLoopContext.setLastNewtonRaphsonResult(runningContext.lastNrResult);
            outerLoopContext.setLoadFlowContext(context);
            outerLoopStatus = outerLoop.check(outerLoopContext, olReporter);
            runningContext.lastOuterLoopStatus = outerLoopStatus;

            if (outerLoopStatus == OuterLoopStatus.UNSTABLE) {
                LOGGER.debug("Start outer loop '{}' iteration {}", outerLoop.getName(), runningContext.outerLoopTotalIterations);

                Reporter nrReporter = context.getNetwork().getReporter();
                if (context.getParameters().getNewtonRaphsonParameters().isDetailedReport()) {
                    nrReporter = Reports.createDetailedNewtonRaphsonReporterOuterLoop(nrReporter,
                            context.getNetwork().getNumCC(),
                            context.getNetwork().getNumSC(),
                            outerLoopIteration.toInteger() + 1, outerLoop.getName());
                }

                // if not yet stable, restart Newton-Raphson
                runningContext.lastNrResult = solver.run(new PreviousValueVoltageInitializer(), nrReporter);

                runningContext.nrTotalIterations.add(runningContext.lastNrResult.getIterations());
                runningContext.outerLoopTotalIterations++;

                outerLoopIteration.increment();
            }
        } while (outerLoopStatus == OuterLoopStatus.UNSTABLE
                && runningContext.lastNrResult.getStatus() == NewtonRaphsonStatus.CONVERGED
                && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());
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
        Solver solver = solverFactory.create(context.getNetwork(),
                                             context.getParameters().getNewtonRaphsonParameters(),
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

        Reporter nrReporter = context.getNetwork().getReporter();
        if (context.getParameters().getNewtonRaphsonParameters().isDetailedReport()) {
            nrReporter = Reports.createDetailedNewtonRaphsonReporter(nrReporter,
                    context.getNetwork().getNumCC(),
                    context.getNetwork().getNumSC());
        }
        // run initial Newton-Raphson
        runningContext.lastNrResult = solver.run(voltageInitializer, nrReporter);

        runningContext.nrTotalIterations.add(runningContext.lastNrResult.getIterations());

        // continue with outer loops only if initial Newton-Raphson succeed
        if (runningContext.lastNrResult.getStatus() == NewtonRaphsonStatus.CONVERGED) {

            // re-run all outer loops until Newton-Raphson failed or no more Newton-Raphson iterations are needed
            int oldNrTotalIterations;
            do {
                oldNrTotalIterations = runningContext.nrTotalIterations.getValue();

                // outer loops are nested: innermost loop first in the list, outermost loop last
                for (var outerLoopAndContext : outerLoopsAndContexts) {
                    runOuterLoop(outerLoopAndContext.getLeft(), outerLoopAndContext.getRight(), solver, runningContext);

                    // continue with next outer loop only if:
                    // - last Newton-Raphson succeed,
                    // - last OuterLoopStatus is not FAILED
                    // - we have not reached max number of outer loop iteration
                    if (runningContext.lastNrResult.getStatus() != NewtonRaphsonStatus.CONVERGED
                            || runningContext.lastOuterLoopStatus == OuterLoopStatus.FAILED
                            || runningContext.outerLoopTotalIterations >= context.getParameters().getMaxOuterLoopIterations()) {
                        break;
                    }
                }
            } while (runningContext.nrTotalIterations.getValue() > oldNrTotalIterations
                    && runningContext.lastNrResult.getStatus() == NewtonRaphsonStatus.CONVERGED
                    && runningContext.lastOuterLoopStatus != OuterLoopStatus.FAILED
                    && runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations());
        }

        double distributedActivePower = 0.0;
        // outer loops finalization (in reverse order to allow correct cleanup)
        for (var outerLoopAndContext : Lists.reverse(outerLoopsAndContexts)) {
            var outerLoop = outerLoopAndContext.getLeft();
            var outerLoopContext = outerLoopAndContext.getRight();
            if (outerLoop instanceof DistributedSlackOuterLoop) {
                distributedActivePower = ((DistributedSlackContextData) outerLoopContext.getData()).getDistributedActivePower();
            }
            outerLoop.cleanup(outerLoopContext);
        }

        final OuterLoopStatus outerLoopFinalStatus;
        if (runningContext.lastOuterLoopStatus == OuterLoopStatus.FAILED) {
            outerLoopFinalStatus = OuterLoopStatus.FAILED;
        } else {
            outerLoopFinalStatus = runningContext.outerLoopTotalIterations < context.getParameters().getMaxOuterLoopIterations()
                    ? OuterLoopStatus.STABLE : OuterLoopStatus.UNSTABLE;
        }

        AcLoadFlowResult result = new AcLoadFlowResult(context.getNetwork(),
                                                       runningContext.outerLoopTotalIterations,
                                                       runningContext.nrTotalIterations.getValue(),
                                                       runningContext.lastNrResult.getStatus(),
                                                       outerLoopFinalStatus,
                                                       runningContext.lastNrResult.getSlackBusActivePowerMismatch(),
                                                       distributedActivePower
                                                       );

        LOGGER.info("Ac loadflow complete on network {} (result={})", context.getNetwork(), result);

        Reports.reportAcLfComplete(context.getNetwork().getReporter(), result.getNewtonRaphsonStatus().name(),
                result.getNewtonRaphsonStatus() == NewtonRaphsonStatus.CONVERGED ? TypedValue.INFO_SEVERITY : TypedValue.ERROR_SEVERITY);

        context.setResult(result);

        return result;
    }

    public static List<AcLoadFlowResult> run(List<LfNetwork> lfNetworks, AcLoadFlowParameters parameters) {
        return lfNetworks.stream()
                .map(n -> {
                    if (n.isValid()) {
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
