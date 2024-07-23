/**
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.DistributedActivePowerContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfArea;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class AreaInterchangeControlOuterloop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedSlackOuterLoop.class);

    public static final String NAME = "AreaInterchangeControl";

    private final double areaInterchangePMaxMismatch;

    private final ActivePowerDistribution activePowerDistribution;

    public AreaInterchangeControlOuterloop(ActivePowerDistribution activePowerDistribution, double areaInterchangePMaxMismatch) {
        this.activePowerDistribution = Objects.requireNonNull(activePowerDistribution);
        this.areaInterchangePMaxMismatch = areaInterchangePMaxMismatch;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        var contextData = new DistributedActivePowerContextData();
        context.setData(contextData);
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {
        List<LfArea> areas = context.getNetwork().getAreas();
        double slackBusActivePowerMismatch = context.getLastSolverResult().getSlackBusActivePowerMismatch();

        List<LfArea> areasToBalance = areas.stream()
                .filter(area -> {
                    double areaActivePowerMismatch = getInterchangeMismatchWithSlack(area, slackBusActivePowerMismatch);
                    double absMismatch = Math.abs(areaActivePowerMismatch);
                    return absMismatch > this.areaInterchangePMaxMismatch / PerUnit.SB && absMismatch > ActivePowerDistribution.P_RESIDUE_EPS;
                })
                .toList();
        boolean shouldBalance = !areasToBalance.isEmpty();

        if (!shouldBalance) {
            // Check Areas interchange without taking slack into account. If an area is not balanced, it means that some slack remains inside it.
            Map<LfArea, Double> interchangeMismatches = areas.stream().filter(area -> {
                double areaInterchangeMismatch = getInterchangeMismatch(area);
                double absMismatch = Math.abs(areaInterchangeMismatch);
                return absMismatch > this.areaInterchangePMaxMismatch / PerUnit.SB && absMismatch > ActivePowerDistribution.P_RESIDUE_EPS;
            }).collect(Collectors.toMap(area -> area, AreaInterchangeControlOuterloop::getInterchangeMismatch));

            if (interchangeMismatches.isEmpty()) {
                LOGGER.debug("Already balanced");
            } else {
                interchangeMismatches.forEach((area, mismatch) ->
                        LOGGER.error("Failed area interchange control : Area {} is not balanced, remains {} MW mismatch", area.getId(), mismatch * PerUnit.SB));
            }
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }
        double totalDistributedActivePower = 0.0;
        int totalIterations = 0;
        boolean movedBuses = false;
        Map<LfArea, Double> areasWithemainingMismatch = new HashMap<>();
        for (LfArea area : areasToBalance) {
            double areaActivePowerMismatch = getInterchangeMismatchWithSlack(area, slackBusActivePowerMismatch);
            ActivePowerDistribution.Result result = activePowerDistribution.run(area.getBuses(), areaActivePowerMismatch);
            double remainingMismatch = result.remainingMismatch();
            double distributedActivePower = areaActivePowerMismatch - remainingMismatch;
            totalDistributedActivePower += distributedActivePower;
            movedBuses |= result.movedBuses();
            totalIterations += result.iteration();
            if (Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
                areasWithemainingMismatch.put(area, remainingMismatch);
            }
        }

        ReportNode iterationReportNode = Reports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
        DistributedActivePowerContextData contextData = (DistributedActivePowerContextData) context.getData();
        contextData.addDistributedActivePower(totalDistributedActivePower);
        int size = areasToBalance.size();
        if (!areasWithemainingMismatch.isEmpty()) {
            final int mismatchAreasCount = areasWithemainingMismatch.size();
            final double largestRemainingMismatch = areasWithemainingMismatch.values().stream().mapToDouble(Double::doubleValue).max().orElse(0) * PerUnit.SB;
            Reports.reportAreaInterchangeControlFailure(iterationReportNode, largestRemainingMismatch, mismatchAreasCount, totalIterations);
            OpenLoadFlowParameters.SlackDistributionFailureBehavior slackDistributionFailureBehavior = context.getLoadFlowContext().getParameters().getSlackDistributionFailureBehavior();
            if (OpenLoadFlowParameters.SlackDistributionFailureBehavior.DISTRIBUTE_ON_REFERENCE_GENERATOR == slackDistributionFailureBehavior) {
                LOGGER.error("Distribute on reference generator is not supported in AreaInterchangeControlOuterloop, falling back to FAIL mode");
                slackDistributionFailureBehavior = OpenLoadFlowParameters.SlackDistributionFailureBehavior.FAIL;
            }
            switch (slackDistributionFailureBehavior) {
                case THROW ->
                    throw new PowsyblException("Failed to distribute interchange active power mismatch in " +
                            mismatchAreasCount + " area(s), largest remaining mismatch is "
                            + largestRemainingMismatch + " MW");

                case LEAVE_ON_SLACK_BUS -> {
                    areasWithemainingMismatch.forEach((area, remainingMismatch) ->
                            LOGGER.warn("Failed to distribute interchange active power mismatch in Area {}, {} MW remains",
                                    area.getId(), remainingMismatch * PerUnit.SB));
                    return new OuterLoopResult(this, movedBuses ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE);
                }
                case FAIL -> {
                    areasWithemainingMismatch.forEach((area, remainingMismatch) -> {
                        String statusText = String.format(Locale.US, "Failed to distribute interchange active power mismatch in area %s, %.2f MW remains", area.getId(), remainingMismatch * PerUnit.SB);
                        LOGGER.error("{}", statusText);
                    });
                    // Mismatches reported in LoadFlowResult on slack bus(es) are the mismatches of the last NR run.
                    // Since we will not be re-running an NR, revert distributedActivePower reporting which would otherwise be misleading.
                    // Said differently, we report that we didn't distribute anything, and this is indeed consistent with the network state.
                    String statusText = String.format(Locale.US, "Failed to distribute interchange active power mismatch in %d areas, %.2f MW remains", mismatchAreasCount, largestRemainingMismatch);
                    contextData.addDistributedActivePower(-totalDistributedActivePower);
                    return new OuterLoopResult(this, OuterLoopStatus.FAILED, statusText);
                }
                default -> throw new IllegalArgumentException("Unknown slackDistributionFailureBehavior");
            }
        } else {
            reportAndLogSuccess(iterationReportNode, totalDistributedActivePower, size, totalIterations);
            return new OuterLoopResult(this, OuterLoopStatus.UNSTABLE);
        }
    }

    private static double getInterchangeMismatch(LfArea area) {
        return area.getInterchangeTarget() - area.getInterchange();
    }

    private static double getInterchangeMismatchWithSlack(LfArea area, double slackBusActivePowerMismatch) {
        return area.getInterchange() - area.getInterchangeTarget() + getAreaSlackInjection(area, slackBusActivePowerMismatch);
    }

    private static double getAreaSlackInjection(LfArea area, double slackBusActivePowerMismatch) {
        List<LfBus> slackBuses = area.getBuses().stream().filter(LfBus::isSlack).toList();
        return slackBuses.isEmpty() ? 0 : slackBusActivePowerMismatch / slackBuses.size();
    }

    private static void reportAndLogSuccess(ReportNode reportNode, double totalDistributedActivePower, int areasCount, int iterationCount) {
        Reports.reportAreaInterchangeControlSuccess(reportNode, totalDistributedActivePower * PerUnit.SB, areasCount, iterationCount);

        LOGGER.info("Area Interchange total mismatch {} MW distributed in {} distribution iteration(s)",
                totalDistributedActivePower * PerUnit.SB, iterationCount);
    }
}
