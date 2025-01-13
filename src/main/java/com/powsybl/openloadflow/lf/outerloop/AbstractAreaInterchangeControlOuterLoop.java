/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.lf.LoadFlowContext;
import com.powsybl.openloadflow.network.LfArea;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public abstract class AbstractAreaInterchangeControlOuterLoop<
            V extends Enum<V> & Quantity,
            E extends Enum<E> & Quantity,
            P extends AbstractLoadFlowParameters<P>,
            C extends LoadFlowContext<V, E, P>,
            O extends AbstractOuterLoopContext<V, E, P, C>>
        extends AbstractActivePowerDistributionOuterLoop<V, E, P, C, O> {

    private final Logger logger;

    protected static final String FAILED_TO_DISTRIBUTE_INTERCHANGE_ACTIVE_POWER_MISMATCH = "Failed to distribute interchange active power mismatch";

    protected static final String DEFAULT_NO_AREA_NAME = "NO_AREA";

    protected final double slackBusPMaxMismatch;
    protected final double areaInterchangePMaxMismatch;
    protected final ActivePowerDistribution activePowerDistribution;
    protected final OuterLoop<V, E, P, C, O> noAreaOuterLoop;

    protected AbstractAreaInterchangeControlOuterLoop(ActivePowerDistribution activePowerDistribution, OuterLoop<V, E, P, C, O> noAreaOuterLoop, double slackBusPMaxMismatch, double areaInterchangePMaxMismatch, Logger logger) {
        this.activePowerDistribution = Objects.requireNonNull(activePowerDistribution);
        this.slackBusPMaxMismatch = slackBusPMaxMismatch;
        this.areaInterchangePMaxMismatch = areaInterchangePMaxMismatch;
        this.logger = logger;
        this.noAreaOuterLoop = noAreaOuterLoop;
    }

    @Override
    public void initialize(O context) {
        LfNetwork network = context.getNetwork();
        if (!network.hasArea() && noAreaOuterLoop != null) {
            noAreaOuterLoop.initialize(context);
            return;
        }
        var contextData = new AreaInterchangeControlContextData(listBusesWithoutArea(network), allocateSlackDistributionParticipationFactors(network));
        context.setData(contextData);
    }

    @Override
    public OuterLoopResult check(O context, ReportNode reportNode) {
        LfNetwork network = context.getNetwork();
        if (!network.hasArea() && noAreaOuterLoop != null) {
            return noAreaOuterLoop.check(context, reportNode);
        }
        double slackBusActivePowerMismatch = getSlackBusActivePowerMismatch(context);
        AreaInterchangeControlContextData contextData = (AreaInterchangeControlContextData) context.getData();
        Map<String, Double> areaSlackDistributionParticipationFactor = contextData.getAreaSlackDistributionParticipationFactor();

        // First, we balance the areas that have a mismatch in their interchange power flow, and take the slack mismatch into account.
        Map<LfArea, Double> areaInterchangeWithSlackMismatches = network.getAreaStream()
                .collect(Collectors.toMap(area -> area, area -> getInterchangeMismatchWithSlack(area, slackBusActivePowerMismatch, areaSlackDistributionParticipationFactor)));
        List<LfArea> areasToBalance = areaInterchangeWithSlackMismatches.entrySet().stream()
                .filter(entry -> {
                    double areaActivePowerMismatch = entry.getValue();
                    return !lessThanInterchangeMaxMismatch(areaActivePowerMismatch);
                })
                .map(Map.Entry::getKey)
                .toList();

        if (areasToBalance.isEmpty()) {
            // Balancing takes the slack mismatch of the Areas into account. Now that the balancing is done, we check only the interchange power flow mismatch.
            // Doing this we make sure that the Areas' interchange targets have been reached and that the slack is correctly distributed.
            Map<String, Double> areaInterchangeMismatches = network.getAreaStream()
                    .filter(area -> {
                        double areaInterchangeMismatch = getInterchangeMismatch(area);
                        return !lessThanInterchangeMaxMismatch(areaInterchangeMismatch);
                    }).collect(Collectors.toMap(LfArea::getId, this::getInterchangeMismatch));

            if (areaInterchangeMismatches.isEmpty() && lessThanSlackBusMaxMismatch(slackBusActivePowerMismatch)) {
                logger.debug("Already balanced");
            } else {
                // If some mismatch remains, we distribute the slack bus active power on the buses without area
                // Corner case: if there is less slack than the slackBusPMaxMismatch, but there are areas with mismatch.
                // We consider that to still distribute the remaining slack will continue to reduce difference between interchange mismatch with slack and interchange mismatch.
                // Which should at the end of the day end up by not having interchange mismatches.
                Set<LfBus> busesWithoutArea = contextData.getBusesWithoutArea();
                Map<String, Pair<Set<LfBus>, Double>> mismatchToDistributeOnBusesWithoutArea = new HashMap<>();
                mismatchToDistributeOnBusesWithoutArea.put(DEFAULT_NO_AREA_NAME, Pair.of(busesWithoutArea, slackBusActivePowerMismatch));
                Map<String, ActivePowerDistribution.Result> resultNoArea = distributeActivePower(mismatchToDistributeOnBusesWithoutArea);

                // If some mismatch remains (when there is no buses without area that participate for example), we distribute equally among the areas.
                double mismatchToSplitAmongAreas = resultNoArea.get(DEFAULT_NO_AREA_NAME).remainingMismatch();
                if (lessThanSlackBusMaxMismatch(mismatchToSplitAmongAreas)) {

                    return buildOuterLoopResult(mismatchToDistributeOnBusesWithoutArea, resultNoArea, reportNode, context);
                } else {
                    int areasCount = (int) network.getAreaStream().count();
                    Map<String, Pair<Set<LfBus>, Double>> mismatchSplitAmongAreas = network.getAreaStream().collect(Collectors.toMap(LfArea::getId, area -> Pair.of(area.getBuses(), mismatchToSplitAmongAreas / areasCount)));
                    Map<String, ActivePowerDistribution.Result> resultByArea = distributeActivePower(mismatchSplitAmongAreas);
                    return buildOuterLoopResult(mismatchSplitAmongAreas, resultByArea, reportNode, context);
                }
            }
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }

        Map<String, Pair<Set<LfBus>, Double>> areasMap = areasToBalance.stream()
                .collect(Collectors.toMap(LfArea::getId, area -> Pair.of(area.getBuses(), getInterchangeMismatchWithSlack(area, slackBusActivePowerMismatch, areaSlackDistributionParticipationFactor))));

        Map<String, ActivePowerDistribution.Result> resultByArea = distributeActivePower(areasMap);
        return buildOuterLoopResult(areasMap, resultByArea, reportNode, context);
    }

    protected Map<String, ActivePowerDistribution.Result> distributeActivePower(Map<String, Pair<Set<LfBus>, Double>> areas) {
        Map<String, ActivePowerDistribution.Result> resultByArea = new HashMap<>();
        for (Map.Entry<String, Pair<Set<LfBus>, Double>> e : areas.entrySet()) {
            double areaActivePowerMismatch = e.getValue().getRight();
            ActivePowerDistribution.Result result = activePowerDistribution.run(null, e.getValue().getLeft(), areaActivePowerMismatch);
            resultByArea.put(e.getKey(), result);
        }
        return resultByArea;
    }

    protected boolean lessThanInterchangeMaxMismatch(double mismatch) {
        return Math.abs(mismatch) <= this.areaInterchangePMaxMismatch / PerUnit.SB || Math.abs(mismatch) <= ActivePowerDistribution.P_RESIDUE_EPS;
    }

    boolean lessThanSlackBusMaxMismatch(double mismatch) {
        return Math.abs(mismatch) <= this.slackBusPMaxMismatch / PerUnit.SB || Math.abs(mismatch) <= ActivePowerDistribution.P_RESIDUE_EPS;
    }

    protected double getInterchangeMismatch(LfArea area) {
        return area.getInterchange() - area.getInterchangeTarget();
    }

    protected double getInterchangeMismatchWithSlack(LfArea area, double slackBusActivePowerMismatch, Map<String, Double> areaSlackDistributionParticipationFactor) {
        return area.getInterchange() - area.getInterchangeTarget() + getSlackInjection(area.getId(), slackBusActivePowerMismatch, areaSlackDistributionParticipationFactor);
    }

    protected double getSlackInjection(String areaId, double slackBusActivePowerMismatch, Map<String, Double> areaSlackDistributionParticipationFactor) {
        return areaSlackDistributionParticipationFactor.getOrDefault(areaId, 0.0) * slackBusActivePowerMismatch;
    }

    protected OuterLoopResult buildOuterLoopResult(Map<String, Pair<Set<LfBus>, Double>> areas, Map<String, ActivePowerDistribution.Result> resultByArea, ReportNode reportNode, O context) {
        Map<String, Double> remainingMismatchByArea = new HashMap<>();
        Map<String, Integer> iterationsByArea = new HashMap<>();
        double totalDistributedActivePower = 0.0;
        boolean movedBuses = false;
        for (Map.Entry<String, ActivePowerDistribution.Result> e : resultByArea.entrySet()) {
            String area = e.getKey();
            ActivePowerDistribution.Result result = e.getValue();
            if (!lessThanInterchangeMaxMismatch(result.remainingMismatch())) {
                remainingMismatchByArea.put(area, result.remainingMismatch());
            }
            totalDistributedActivePower += areas.get(area).getRight() - result.remainingMismatch();
            movedBuses |= result.movedBuses();
            iterationsByArea.put(area, result.iteration());
        }

        ReportNode iterationReportNode = Reports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
        AreaInterchangeControlContextData contextData = (AreaInterchangeControlContextData) context.getData();
        contextData.addDistributedActivePower(totalDistributedActivePower);
        if (!remainingMismatchByArea.isEmpty()) {
            logger.error(FAILED_TO_DISTRIBUTE_INTERCHANGE_ACTIVE_POWER_MISMATCH);
            ReportNode failureReportNode = Reports.reportAreaInterchangeControlDistributionFailure(iterationReportNode);
            remainingMismatchByArea.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                logger.error("Remaining mismatch for Area {}: {} MW", entry.getKey(), entry.getValue() * PerUnit.SB);
                Reports.reportAreaInterchangeControlAreaMismatch(failureReportNode, entry.getKey(), entry.getValue() * PerUnit.SB);
            });
            switch (context.getLoadFlowContext().getParameters().getSlackDistributionFailureBehavior()) {
                case THROW ->
                    throw new PowsyblException(FAILED_TO_DISTRIBUTE_INTERCHANGE_ACTIVE_POWER_MISMATCH);
                case LEAVE_ON_SLACK_BUS -> {
                    return new OuterLoopResult(this, movedBuses ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE);
                }
                case FAIL, DISTRIBUTE_ON_REFERENCE_GENERATOR -> {
                    // Mismatches reported in LoadFlowResult on slack bus(es) are the mismatches of the last solver (DC, NR, ...) run.
                    // Since we will not be re-running the solver, revert distributedActivePower reporting which would otherwise be misleading.
                    // Said differently, we report that we didn't distribute anything, and this is indeed consistent with the network state.
                    contextData.addDistributedActivePower(-totalDistributedActivePower);
                    return new OuterLoopResult(this, OuterLoopStatus.FAILED, FAILED_TO_DISTRIBUTE_INTERCHANGE_ACTIVE_POWER_MISMATCH);
                }
                default -> throw new IllegalStateException("Unexpected SlackDistributionFailureBehavior value");
            }
        } else {
            if (movedBuses) {
                areas.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    logger.info("Area {} interchange mismatch ({} MW) distributed in {} distribution iteration(s)", entry.getKey(), entry.getValue().getValue() * PerUnit.SB, iterationsByArea.get(entry.getKey()));
                    Reports.reportAreaInterchangeControlAreaDistributionSuccess(iterationReportNode, entry.getKey(), entry.getValue().getValue() * PerUnit.SB, iterationsByArea.get(entry.getKey()));
                });
                return new OuterLoopResult(this, OuterLoopStatus.UNSTABLE);
            } else {
                return new OuterLoopResult(this, OuterLoopStatus.STABLE);
            }
        }
    }

    protected Set<LfBus> listBusesWithoutArea(LfNetwork network) {
        return network.getBuses().stream()
                .filter(b -> b.getArea().isEmpty())
                .filter(b -> !b.isFictitious())
                .collect(Collectors.toSet());
    }

    protected Map<String, Double> allocateSlackDistributionParticipationFactors(LfNetwork lfNetwork) {
        Map<String, Double> areaSlackDistributionParticipationFactor = new HashMap<>();
        List<LfBus> slackBuses = lfNetwork.getSlackBuses();
        int totalSlackBusCount = slackBuses.size();
        for (LfBus slackBus : slackBuses) {
            Optional<LfArea> areaOpt = slackBus.getArea();
            if (areaOpt.isPresent()) {
                areaSlackDistributionParticipationFactor.put(areaOpt.get().getId(), areaSlackDistributionParticipationFactor.getOrDefault(areaOpt.get().getId(), 0.0) + 1.0 / totalSlackBusCount);
            } else {
                // When a bus is connected to one or multiple Areas but the flow through the bus is not considered for those areas' interchange power flow,
                // its slack injection should be considered for the slack of some Areas that it is connected to.
                Set<LfBranch> connectedBranches = new HashSet<>(slackBus.getBranches());
                Set<LfArea> connectedAreas = connectedBranches.stream()
                        .flatMap(branch -> Stream.of(branch.getBus1(), branch.getBus2()))
                        .filter(Objects::nonNull)
                        .map(LfBus::getArea)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toSet());

                // If the slack bus is on a boundary point considered for net position,
                // it will resolve naturally because deviations caused by slack are already present on tie line flows
                // no need to include in any area net position calculation
                Set<LfArea> areasSharingSlack = connectedAreas.stream()
                        .filter(area -> area.getBoundaries().stream().noneMatch(boundary -> connectedBranches.contains(boundary.getBranch())))
                        .collect(Collectors.toSet());
                if (!areasSharingSlack.isEmpty()) {
                    areasSharingSlack.forEach(area -> areaSlackDistributionParticipationFactor.put(area.getId(), areaSlackDistributionParticipationFactor.getOrDefault(area.getId(), 0.0) + 1.0 / areasSharingSlack.size() / totalSlackBusCount));
                    logger.warn("Slack bus {} is not in any Area and is connected to Areas: {}. Areas {} are not considering the flow through this bus for their interchange flow. The slack will be distributed between those areas.",
                            slackBus.getId(), connectedAreas.stream().map(LfArea::getId).toList(), areasSharingSlack.stream().map(LfArea::getId).toList());
                } else {
                    areaSlackDistributionParticipationFactor.put(DEFAULT_NO_AREA_NAME, areaSlackDistributionParticipationFactor.getOrDefault(DEFAULT_NO_AREA_NAME, 0.0) + 1.0 / totalSlackBusCount);
                }

            }
        }
        return areaSlackDistributionParticipationFactor;
    }
}
