/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.util;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class ActivePowerDistribution {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivePowerDistribution.class);

    /**
     * Active power residue epsilon: 10^-5 in p.u => 10^-3 in Mw
     */
    public static final double P_RESIDUE_EPS = Math.pow(10, -5);

    public interface Step {

        String getElementType();

        List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses, double activePowerMismatch);

        double run(List<ParticipatingElement> participatingElements, int iteration, double remainingMismatch);
    }

    public record Result(int iteration, double remainingMismatch, boolean movedBuses) { }

    private final ActivePowerDistribution.Step step;

    private ActivePowerDistribution(Step step) {
        this.step = Objects.requireNonNull(step);
    }

    public String getElementType() {
        return step.getElementType();
    }

    public Result run(LfNetwork network, double activePowerMismatch) {
        return run(network.getReferenceGenerator(), network.getBuses(), activePowerMismatch);
    }

    public Result run(LfGenerator referenceGenerator, Collection<LfBus> buses, double activePowerMismatch) {
        List<ParticipatingElement> participatingElements = step.getParticipatingElements(buses, activePowerMismatch);
        final Map<ParticipatingElement, Double> initialP = participatingElements.stream()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ParticipatingElement::getTargetP));

        int iteration = 0;
        double remainingMismatch = activePowerMismatch;

        if (referenceGenerator != null) {
            // "undo" everything from targetP to go back to initialP for reference generator
            remainingMismatch -= referenceGenerator.getInitialTargetP() - referenceGenerator.getTargetP();
            referenceGenerator.setTargetP(referenceGenerator.getInitialTargetP());
        }

        while (!participatingElements.isEmpty()
                && Math.abs(remainingMismatch) > P_RESIDUE_EPS) {

            if (ParticipatingElement.participationFactorNorm(participatingElements) > 0.0) {
                double done = step.run(participatingElements, iteration, remainingMismatch);
                remainingMismatch -= done;
            } else {
                break;
            }
            iteration++;
        }

        // Identify if injections moved significantly, used e.g. to establish stable/unstable outer loop status.
        // The 0.9 magic factor is to handle potential rounding issues.
        final boolean movedBuses = initialP.entrySet().stream()
                .mapToDouble(e -> Math.abs(e.getKey().getTargetP() - e.getValue())).sum() > P_RESIDUE_EPS * 0.9;

        return new Result(iteration, remainingMismatch, movedBuses);
    }

    public static ActivePowerDistribution create(LoadFlowParameters.BalanceType balanceType, boolean loadPowerFactorConstant, boolean useActiveLimits) {
        return new ActivePowerDistribution(getStep(balanceType, loadPowerFactorConstant, useActiveLimits));
    }

    public static Step getStep(LoadFlowParameters.BalanceType balanceType, boolean loadPowerFactorConstant, boolean useActiveLimits) {
        return switch (balanceType) {
            case PROPORTIONAL_TO_LOAD, PROPORTIONAL_TO_CONFORM_LOAD ->
                    new LoadActivePowerDistributionStep(loadPowerFactorConstant);
            case PROPORTIONAL_TO_GENERATION_P_MAX ->
                    new GenerationActivePowerDistributionStep(GenerationActivePowerDistributionStep.ParticipationType.MAX, useActiveLimits);
            case PROPORTIONAL_TO_GENERATION_P ->
                    new GenerationActivePowerDistributionStep(GenerationActivePowerDistributionStep.ParticipationType.TARGET, useActiveLimits);
            case PROPORTIONAL_TO_GENERATION_PARTICIPATION_FACTOR ->
                    new GenerationActivePowerDistributionStep(GenerationActivePowerDistributionStep.ParticipationType.PARTICIPATION_FACTOR, useActiveLimits);
            case PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN ->
                    new GenerationActivePowerDistributionStep(GenerationActivePowerDistributionStep.ParticipationType.REMAINING_MARGIN, useActiveLimits);
        };
    }

    public record ResultWithFailureBehaviorHandling(boolean failed, String failedMessage, int iteration, double remainingMismatch, boolean movedBuses, double failedDistributedActivePower) { }

    public static ResultWithFailureBehaviorHandling handleDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior behavior,
                                                                                      LfGenerator referenceGenerator,
                                                                                      double activePowerMismatch,
                                                                                      Result result, String failMessageTemplate) {
        Objects.requireNonNull(behavior);
        Objects.requireNonNull(result);
        ResultWithFailureBehaviorHandling resultWithFailureBehaviorHandling;

        final OpenLoadFlowParameters.SlackDistributionFailureBehavior effectiveBehavior;
        // if requested behavior is to distribute on reference generator, but there is no reference generator, we fall back internally to FAIL mode
        if (OpenLoadFlowParameters.SlackDistributionFailureBehavior.DISTRIBUTE_ON_REFERENCE_GENERATOR == behavior && referenceGenerator == null) {
            effectiveBehavior = OpenLoadFlowParameters.SlackDistributionFailureBehavior.FAIL;
            LOGGER.debug("Distribution failure behavior is DISTRIBUTE_ON_REFERENCE_GENERATOR but no reference generator selected, switching to FAIL mode");
        } else {
            effectiveBehavior = behavior;
        }

        final double distributedActivePower = activePowerMismatch - result.remainingMismatch();

        if (Math.abs(result.remainingMismatch()) > ActivePowerDistribution.P_RESIDUE_EPS) {

            String statusText = String.format(Locale.US, failMessageTemplate, result.remainingMismatch() * PerUnit.SB);
            switch (effectiveBehavior) {
                case THROW ->
                        throw new PowsyblException(statusText);

                case LEAVE_ON_SLACK_BUS -> {
                    LOGGER.warn(statusText);
                    resultWithFailureBehaviorHandling = new ResultWithFailureBehaviorHandling(false, statusText, result.iteration(), result.remainingMismatch(), result.movedBuses(), 0.0);
                }
                case FAIL -> {
                    LOGGER.error(statusText);
                    // Mismatches reported in LoadFlowResult on slack bus(es) are the mismatches of the last solver (DC, NR, ...) run.
                    // Since we will not be re-running the solver, revert distributedActivePower reporting which would otherwise be misleading.
                    // Said differently, we report that we didn't distribute anything, and this is indeed consistent with the network state.
                    resultWithFailureBehaviorHandling = new ResultWithFailureBehaviorHandling(true, statusText, result.iteration(), result.remainingMismatch(), result.movedBuses(), distributedActivePower);
                }
                case DISTRIBUTE_ON_REFERENCE_GENERATOR -> {
                    Objects.requireNonNull(referenceGenerator, "No reference generator");
                    // remaining goes to reference generator, without any limit consideration
                    LOGGER.debug("{} MW distributed to reference generator '{}'",
                            result.remainingMismatch() * PerUnit.SB, referenceGenerator.getId());
                    referenceGenerator.setTargetP(referenceGenerator.getTargetP() + result.remainingMismatch());
                    // one more iteration, no more remaining mismatch, bus moved
                    resultWithFailureBehaviorHandling = new ResultWithFailureBehaviorHandling(false, statusText, result.iteration() + 1, 0.0, true, 0.0);
                }
                default -> throw new IllegalArgumentException("Unknown slackDistributionFailureBehavior");
            }
        } else {
            resultWithFailureBehaviorHandling = new ResultWithFailureBehaviorHandling(false, "", result.iteration(), result.remainingMismatch(), result.movedBuses(), 0.0);
        }

        return resultWithFailureBehaviorHandling;
    }

    public static void reportAndLogSuccess(ReportNode reportNode, double slackBusActivePowerMismatch, ResultWithFailureBehaviorHandling result) {
        Reports.reportMismatchDistributionSuccess(reportNode, slackBusActivePowerMismatch * PerUnit.SB, result.iteration());

        LOGGER.info("Slack bus active power ({} MW) distributed in {} distribution iteration(s)",
                slackBusActivePowerMismatch * PerUnit.SB, result.iteration());
    }
}
