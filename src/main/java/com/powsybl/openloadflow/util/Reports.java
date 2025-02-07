/*
 * Copyright (c) 2022-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.report.TypedValue;
import com.powsybl.openloadflow.OpenLoadFlowReportConstants;
import com.powsybl.openloadflow.network.LfBus;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class Reports {

    private static final String NETWORK_NUM_CC = "networkNumCc";
    private static final String NETWORK_NUM_SC = "networkNumSc";
    private static final String ITERATION = "iteration";
    private static final String ITERATION_COUNT = "iterationCount";
    private static final String NETWORK_ID = "networkId";
    private static final String IMPACTED_GENERATOR_COUNT = "impactedGeneratorCount";

    private static final String IMPACTED_TRANSFORMER_COUNT = "impactedTransformerCount";

    private static final String IMPACTED_SHUNT_COUNT = "impactedShuntCount";
    private static final String BUS_ID = "busId";
    private static final String GENERATORS_ID = "generatorIds";
    private static final String CONTROLLER_BUS_ID = "controllerBusId";
    private static final String CONTROLLED_BUS_ID = "controlledBusId";
    public static final String MISMATCH = "mismatch";

    public record BusReport(String busId, double mismatch, double nominalV, double v, double phi, double p, double q) {
    }

    private Reports() {
    }

    public static void reportNetworkSize(ReportNode reportNode, int busCount, int branchCount) {
        reportNode.newReportNode()
                .withMessageTemplate("networkSize", "Network has ${busCount} buses and ${branchCount} branches")
                .withUntypedValue("busCount", busCount)
                .withUntypedValue("branchCount", branchCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportNetworkBalance(ReportNode reportNode, double activeGeneration, double activeLoad, double reactiveGeneration, double reactiveLoad) {
        reportNode.newReportNode()
                .withMessageTemplate("networkBalance", "Network balance: active generation=${activeGeneration} MW, active load=${activeLoad} MW, reactive generation=${reactiveGeneration} MVar, reactive load=${reactiveLoad} MVar")
                .withUntypedValue("activeGeneration", activeGeneration)
                .withUntypedValue("activeLoad", activeLoad)
                .withUntypedValue("reactiveGeneration", reactiveGeneration)
                .withUntypedValue("reactiveLoad", reactiveLoad)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportNotUniqueControlledBusKeepingFirstControl(ReportNode reportNode, String generatorIds, String controllerBusId, String controlledBusId, String controlledBusGenId) {
        reportNode.newReportNode()
                .withMessageTemplate("notUniqueControlledBusKeepingFirstControl", "Generators [${generatorIds}] are connected to the same bus ${controllerBusId} but control the voltage of different buses: ${controlledBusId} (kept) and ${controlledBusGenId} (rejected)")
                .withUntypedValue(GENERATORS_ID, generatorIds)
                .withUntypedValue(CONTROLLER_BUS_ID, controllerBusId)
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withUntypedValue("controlledBusGenId", controlledBusGenId)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportNotUniqueControlledBusDisablingControl(ReportNode reportNode, String generatorIds, String controllerBusId, String controlledBusId, String controlledBusGenId) {
        reportNode.newReportNode()
                .withMessageTemplate("notUniqueControlledBusDisablingControl", "Generators [${generatorIds}] are connected to the same bus ${controllerBusId} but control the voltage of different buses (${controlledBusId} and ${controlledBusGenId}): disabling voltage control")
                .withUntypedValue(GENERATORS_ID, generatorIds)
                .withUntypedValue(CONTROLLER_BUS_ID, controllerBusId)
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withUntypedValue("controlledBusGenId", controlledBusGenId)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportNotUniqueTargetVControllerBusKeepingFirstControl(ReportNode reportNode, String generatorIds, String controllerBusId, Double keptTargetV, Double rejectedTargetV) {
        reportNode.newReportNode()
                .withMessageTemplate("notUniqueTargetVControllerBusKeepingFirstControl", "Generators [${generatorIds}] are connected to the same bus ${controllerBusId} with different target voltages: ${keptTargetV} kV (kept) and ${rejectedTargetV} kV (rejected)")
                .withUntypedValue(GENERATORS_ID, generatorIds)
                .withUntypedValue(CONTROLLER_BUS_ID, controllerBusId)
                .withUntypedValue("keptTargetV", keptTargetV)
                .withUntypedValue("rejectedTargetV", rejectedTargetV)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportNotUniqueTargetVControllerBusDisablingControl(ReportNode reportNode, String generatorIds, String controllerBusId, Double targetV1, Double targetV2) {
        reportNode.newReportNode()
                .withMessageTemplate("notUniqueTargetVControllerBusDisablingControl", "Generators [${generatorIds}] are connected to the same bus ${controllerBusId} with different target voltages (${targetV1} kV and ${targetV2} kV): disabling voltage control")
                .withUntypedValue(GENERATORS_ID, generatorIds)
                .withUntypedValue(CONTROLLER_BUS_ID, controllerBusId)
                .withUntypedValue("targetV1", targetV1)
                .withUntypedValue("targetV2", targetV2)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportControllerShuntAlreadyInVoltageControl(ReportNode reportNode, String controllerShuntId, String controlledBusId) {
        reportNode.newReportNode()
                .withMessageTemplate("controllerShuntAlreadyInVoltageControl", "Controller shunt ${controllerShuntId} is already in a shunt voltage control. The second controlled bus ${controlledBusId} is ignored")
                .withUntypedValue("controllerShuntId", controllerShuntId)
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportBusAlreadyControlledWithDifferentTargetV(ReportNode reportNode, String controllerBusId, String controlledBusId, String busesId, Double keptTargetV, Double ignoredTargetV) {
        reportNode.newReportNode()
                .withMessageTemplate("busAlreadyControlledWithDifferentTargetV", "Bus ${controllerBusId} controls voltage of bus ${controlledBusId} which is already controlled by buses [${busesId}] with a different target voltage: ${keptTargetV} kV (kept) and ${ignoredTargetV} kV (ignored)")
                .withUntypedValue(CONTROLLER_BUS_ID, controllerBusId)
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withUntypedValue("busesId", busesId)
                .withUntypedValue("keptTargetV", keptTargetV)
                .withUntypedValue("ignoredTargetV", ignoredTargetV)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportBranchControlledAtBothSides(ReportNode reportNode, String controlledBranchId, String keptSide, String rejectedSide) {
        reportNode.newReportNode()
                .withMessageTemplate("branchControlledAtBothSides", "Controlled branch ${controlledBranchId} is controlled at both sides. Controlled side ${keptSide} (kept) side ${rejectedSide} (rejected).")
                .withUntypedValue("controlledBranchId", controlledBranchId)
                .withUntypedValue("keptSide", keptSide)
                .withUntypedValue("rejectedSide", rejectedSide)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportNetworkMustHaveAtLeastOneBusGeneratorVoltageControlEnabled(ReportNode reportNode) {
        reportNode.newReportNode()
                .withMessageTemplate("networkMustHaveAtLeastOneBusGeneratorVoltageControlEnabled", "Network must have at least one bus with generator voltage control enabled")
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportComponentsWithoutGenerators(ReportNode reportNode, int deadComponentsCount) {
        reportNode.newReportNode()
                .withMessageTemplate("componentsWithoutGenerators", "No calculation will be done on ${deadComponentsCount} network(s) that have no generators")
                .withUntypedValue("deadComponentsCount", deadComponentsCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportMismatchDistributionFailure(ReportNode reportNode, double remainingMismatch) {
        reportNode.newReportNode()
                .withMessageTemplate("mismatchDistributionFailure", "Failed to distribute slack bus active power mismatch, ${mismatch} MW remains")
                .withTypedValue(MISMATCH, remainingMismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportMismatchDistributionSuccess(ReportNode reportNode, double slackBusActivePowerMismatch, int iterationCount) {
        reportNode.newReportNode()
                .withMessageTemplate("mismatchDistributionSuccess", "Slack bus active power (${initialMismatch} MW) distributed in ${iterationCount} distribution iteration(s)")
                .withTypedValue("initialMismatch", slackBusActivePowerMismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withUntypedValue(ITERATION_COUNT, iterationCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportAreaNoInterchangeControl(ReportNode reportNode, String area, String reason) {
        reportNode.newReportNode()
                .withMessageTemplate("areaNoInterchangeControl", "Area ${area} will not be considered in area interchange control, reason: ${reason}")
                .withUntypedValue("area", area)
                .withUntypedValue("reason", reason)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static ReportNode reportAreaInterchangeControlDistributionFailure(ReportNode reportNode) {
        return reportNode.newReportNode()
                .withMessageTemplate("areaInterchangeControlDistributionFailure", "Failed to distribute interchange active power mismatch")
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportAreaInterchangeControlAreaMismatch(ReportNode reportNode, String area, double mismatch) {
        reportNode.newReportNode()
                .withMessageTemplate("areaInterchangeControlAreaMismatch", "Remaining mismatch for Area ${area}: ${mismatch} MW")
                .withUntypedValue("area", area)
                .withTypedValue(MISMATCH, mismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportAreaInterchangeControlAreaDistributionSuccess(ReportNode reportNode, String area, double mismatch, int iterationCount) {
        reportNode.newReportNode()
                .withMessageTemplate("areaInterchangeControlAreaDistributionSuccess", "Area ${area} interchange mismatch (${mismatch} MW) distributed in ${iterationCount} distribution iteration(s)")
                .withUntypedValue("area", area)
                .withTypedValue(MISMATCH, mismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withUntypedValue(ITERATION_COUNT, iterationCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static ReportNode reportPvToPqBuses(ReportNode reportNode, int pvToPqBusCount, int remainingPvBusCount) {
        return reportNode.newReportNode()
                .withMessageTemplate("switchPvPq", "${pvToPqBusCount} buses switched PV -> PQ (${remainingPvBusCount} buses remain PV)")
                .withUntypedValue("pvToPqBusCount", pvToPqBusCount)
                .withUntypedValue("remainingPvBusCount", remainingPvBusCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportPvToPqMaxQ(ReportNode reportNode,
                                        LfBus controllerBus,
                                        double busQ,
                                        double maxQ,
                                        boolean log,
                                        Logger logger) {
        ReportNode newNode = reportNode.newReportNode()
                .withMessageTemplate("switchPvPqQMax",
                        "Switch bus '${busId}' PV -> PQ, q=${busQ} > maxQ=${maxQ}")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busQ", round2(busQ * PerUnit.SB))
                .withUntypedValue("maxQ", round2(maxQ * PerUnit.SB))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
        if (log) {
            logger.trace(newNode.getMessage());
        }
    }

    public static void reportPvToPqMinQ(ReportNode reportNode,
                                        LfBus controllerBus,
                                        double busQ,
                                        double minQ,
                                        boolean log,
                                        Logger logger) {
        ReportNode newNode = reportNode.newReportNode()
                .withMessageTemplate("switchPvPqQMin",
                        "Switch bus '${busId}' PV -> PQ, q=${busQ} < minQ=${minQ}")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busQ", round2(busQ * PerUnit.SB))
                .withUntypedValue("minQ", round2(minQ * PerUnit.SB))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
        if (log) {
            logger.trace(newNode.getMessage());
        }
    }

    public static void reportPvToPqMinRealisticV(ReportNode reportNode,
                                                 LfBus controllerBus,
                                                 double targetQ,
                                                 double minRealisticV,
                                                 boolean log,
                                                 Logger logger) {
        ReportNode newNode = reportNode.newReportNode()
                .withMessageTemplate("switchPvPqVMax",
                        "Switch bus '${busId}' PV -> PQ, q set to ${targetQ} = targetQ - V < ${minRealisticV}kV when remote voltage target is maintained")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("targetQ", round2(targetQ * PerUnit.SB))
                .withUntypedValue("minRealisticV", round2(minRealisticV * controllerBus.getNominalV()))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
        if (log) {
            logger.trace(newNode.getMessage());
        }
    }

    public static void reportPvToPqMaxRealisticV(ReportNode reportNode,
                                                 LfBus controllerBus,
                                                 double targetQ,
                                                 double maxRealisticV,
                                                 boolean log,
                                                 Logger logger) {
        ReportNode newNode = reportNode.newReportNode()
                .withMessageTemplate("switchPvPqVMin",
                        "Switch bus '${busId}' PV -> PQ, q set to ${targetQ} = targetQ - V > ${maxRealisticV}kV when remote voltage target is maintained")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("targetQ", round2(targetQ * PerUnit.SB))
                .withUntypedValue("maxRealisticV", round2(maxRealisticV * controllerBus.getNominalV()))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
        if (log) {
            logger.trace(newNode.getMessage());
        }
    }

    public static ReportNode reportPqToPvBuses(ReportNode reportNode, int pqToPvBusCount, int blockedPqBusCount) {
        return reportNode.newReportNode()
                .withMessageTemplate("switchPqPv", "${pqToPvBusCount} buses switched PQ -> PV (${blockedPqBusCount} buses blocked PQ due to the max number of switches)")
                .withUntypedValue("pqToPvBusCount", pqToPvBusCount)
                .withUntypedValue("blockedPqBusCount", blockedPqBusCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static ReportNode reportPqSwitchLimit(LfBus controllerBus, int limit, boolean log, Logger logger) {
        ReportNode result = ReportNode.newRootReportNode()
                .withMessageTemplate("reportPqSwitchLimit",
                        "Bus '${busId}' blocked PQ as it has reach its max number of PQ -> PV switch (${limit})")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("limit", limit)
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .build();
        if (log) {
            logger.trace(result.getMessage());
        }
        return result;
    }

    public static ReportNode reportPqToPvBusMaxLimit(LfBus controllerBus, LfBus controlledBus, double targetV, boolean log, Logger logger) {
        ReportNode result = ReportNode.newRootReportNode()
                .withMessageTemplate("reportPqToPvBus",
                        "Switch bus '${busId}' PQ -> PV, q=maxQ and v=${busV}kV > targetV=${targetV}kV")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busV", controlledBus != null ?
                        round4(controlledBus.getV() * controlledBus.getNominalV())
                        :
                        Double.NaN)
                .withUntypedValue("targetV", controlledBus != null ?
                        round4(targetV * controlledBus.getNominalV())
                        :
                        Double.NaN)
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .build();
        if (log) {
            logger.trace(result.getMessage());
        }
        return result;
    }

    public static ReportNode reportPqToPvBusMinLimit(LfBus controllerBus, LfBus controlledBus, double targetV, boolean log, Logger logger) {
        ReportNode result = ReportNode.newRootReportNode()
                .withMessageTemplate("reportPqToPvBus",
                        "Switch bus '${busId}' PQ -> PV, q=minQ and v=${busV}kV < targetV=${targetV}kV")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busV", controlledBus != null ?
                        round4(controlledBus.getV() * controlledBus.getNominalV())
                        :
                        Double.NaN)
                .withUntypedValue("targetV", controlledBus != null ?
                        round4(targetV * controlledBus.getNominalV())
                        :
                        Double.NaN)
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .build();
        if (log) {
            logger.trace(result.getMessage());
        }
        return result;
    }

    public static void reportBusForcedToBePv(ReportNode reportNode, String busId) {
        reportNode.newReportNode()
                .withMessageTemplate("busForcedToBePv", "All PV buses should switch PQ, strongest one will stay PV: ${busId}")
                .withUntypedValue(BUS_ID, busId)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportBusesWithUpdatedQLimits(ReportNode reportNode, int numBusesWithUpdatedQLimits) {
        reportNode.newReportNode()
                .withMessageTemplate("busWithUpdatedQLimits", "${numBusesWithUpdatedQLimits} buses blocked at a reactive limit have been adjusted because the reactive limit changed")
                .withUntypedValue("numBusesWithUpdatedQLimits", numBusesWithUpdatedQLimits)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static ReportNode reportReactiveControllerBusesToPqBuses(ReportNode reportNode, int remoteReactivePowerControllerBusToPqCount) {
        return reportNode.newReportNode()
                .withMessageTemplate("remoteReactiveControllerBusToPq", "${remoteReactivePowerControllerBusToPqCount} bus(es) with remote reactive power controller switched PQ")
                .withUntypedValue("remoteReactivePowerControllerBusToPqCount", remoteReactivePowerControllerBusToPqCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100d;
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000d;
    }

    public static ReportNode reportReactiveControllerBusesToPqMaxQ(LfBus controllerBus,
                                                                   double busQ,
                                                                   double maxQ,
                                                                   boolean log,
                                                                   Logger logger) {
        ReportNode result = ReportNode.newRootReportNode()
                .withMessageTemplate("reportReactiveControllerBusesToPqMaxQ",
                        "Remote reactive power controller bus '${busId}' -> PQ, q=${busQ} > maxQ=${maxQ}")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busQ", round2(busQ * PerUnit.SB))
                .withUntypedValue("maxQ", round2(maxQ * PerUnit.SB))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .build();
        if (log) {
            logger.trace(result.getMessage());
        }
        return result;
    }

    public static ReportNode reportReactiveControllerBusesToPqMinQ(LfBus controllerBus,
                                                             double busQ,
                                                             double minQ,
                                                             boolean log,
                                                             Logger logger) {
        ReportNode result = ReportNode.newRootReportNode()
                .withMessageTemplate("reportReactiveControllerBusesToPqMinQ",
                        "Remote reactive power controller bus '${busId}' -> PQ, q=${busQ} < minQ=${minQ}")
                .withUntypedValue("busId", controllerBus.getId())
                .withUntypedValue("busQ", round2(busQ * PerUnit.SB))
                .withUntypedValue("minQ", round2(minQ * PerUnit.SB))
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .build();
        if (log) {
            logger.trace(result.getMessage());
        }
        return result;
    }

    public static void reportStandByAutomatonActivation(ReportNode reportNode, String busId, double newTargetV) {
        reportNode.newReportNode()
                .withMessageTemplate("standByAutomatonActivation", "Activation of voltage control of static var compensator with stand by automaton: bus ${busId} switched PQ -> PV with targetV ${newTargetV}")
                .withUntypedValue(BUS_ID, busId)
                .withUntypedValue("newTargetV", newTargetV)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportCurrentLimiterPstsChangedTaps(ReportNode reportNode, int numOfCurrentLimiterPstsThatChangedTap) {
        reportNode.newReportNode()
                .withMessageTemplate("currentLimiterPstsChangedTaps", "${numOfCurrentLimiterPstsThatChangedTap} current limiter PST(s) changed taps")
                .withUntypedValue("numOfCurrentLimiterPstsThatChangedTap", numOfCurrentLimiterPstsThatChangedTap)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportActivePowerControlPstsChangedTaps(ReportNode reportNode, int numOfActivePowerControlPstsThatChangedTap) {
        reportNode.newReportNode()
                .withMessageTemplate("activePowerControlPstsChangedTaps", "${numOfActivePowerControlPstsThatChangedTap} active power control PST(s) changed taps")
                .withUntypedValue("numOfActivePowerControlPstsThatChangedTap", numOfActivePowerControlPstsThatChangedTap)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportTransformerControlAlreadyExistsWithDifferentTargetV(ReportNode reportNode, String firstControllerId, String newControllerId, String controlledBusId, double vcTargetValue, double targetValue) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlAlreadyExistsWithDifferentTargetV", "Transformers ${firstControllerId} and ${newControllerId} control voltage at bus ${controlledBusId} with different target voltages: ${vcTargetValue}kV (kept) and ${targetValue}kV (rejected)")
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withUntypedValue("firstControllerId", firstControllerId)
                .withUntypedValue("newControllerId", newControllerId)
                .withUntypedValue("vcTargetValue", vcTargetValue)
                .withUntypedValue("targetValue", targetValue)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportTransformerControlAlreadyExistsUpdateDeadband(ReportNode reportNode, String firstControllerId, String newControllerId, String controlledBusId, double newTargetDeadband, Double oldTargetDeadband) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlAlreadyExistsUpdateDeadband", "Transformers ${firstControllerId} and ${newControllerId} control voltage at bus ${controlledBusId} with different deadbands, thinnest will be kept: ${newTargetDeadband}kV (kept) and ${oldTargetDeadband}kV (rejected)")
                .withUntypedValue(CONTROLLED_BUS_ID, controlledBusId)
                .withUntypedValue("firstControllerId", firstControllerId)
                .withUntypedValue("newControllerId", newControllerId)
                .withUntypedValue("newTargetDeadband", newTargetDeadband)
                .withUntypedValue("oldTargetDeadband", oldTargetDeadband == null ? "---" : oldTargetDeadband.toString())
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportTransformerControlBusesOutsideDeadband(ReportNode reportNode, int numTransformerControlBusesOutsideDeadband) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlBusesOutsideDeadband", "${numTransformerControlBusesOutsideDeadband} voltage-controlled buses are outside of their target deadbands")
                .withUntypedValue("numTransformerControlBusesOutsideDeadband", numTransformerControlBusesOutsideDeadband)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportTransformerControlBranchesOutsideDeadband(ReportNode reportNode, int numTransformerControlBranchesOutsideDeadband) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlBranchesOutsideDeadband", "${numTransformerControlBranchesOutsideDeadband} reactive power-controlled branches are outside of their target deadbands")
                .withUntypedValue("numTransformerControlBranchesOutsideDeadband", numTransformerControlBranchesOutsideDeadband)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportTransformerControlChangedTaps(ReportNode reportNode, int numTransformerControlAdjusted) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlChangedTaps", "${numTransformerControlAdjusted} transformers changed tap position")
                .withUntypedValue("numTransformerControlAdjusted", numTransformerControlAdjusted)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportTransformerControlTapLimit(ReportNode reportNode, int numTransformerControlTapLimit) {
        reportNode.newReportNode()
                .withMessageTemplate("transformerControlTapLimit", "${numTransformerControlTapLimit} transformers reached their tap maximum position")
                .withUntypedValue("numTransformerControlTapLimit", numTransformerControlTapLimit)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportShuntVoltageControlChangedSection(ReportNode reportNode, int numShuntVoltageControlAdjusted) {
        reportNode.newReportNode()
                .withMessageTemplate("shuntVoltageControlChangedSection", "${numShuntVoltageControlAdjusted} shunts changed section")
                .withUntypedValue("numShuntVoltageControlAdjusted", numShuntVoltageControlAdjusted)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportUnsuccessfulOuterLoop(ReportNode reportNode, String outerLoopStatus) {
        reportNode.newReportNode()
                .withMessageTemplate("outerLoopStatus", "Outer loop unsuccessful with status: ${outerLoopStatus}")
                .withUntypedValue("outerLoopStatus", outerLoopStatus)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportMaxOuterLoopIterations(ReportNode reportNode, int iterationCount, boolean withLog, Logger logger) {
        ReportNode added = reportNode.newReportNode()
                .withMessageTemplate("maxOuterLoopIterations", "Maximum number of outerloop iterations reached: ${outerLoopIterationCount}")
                .withUntypedValue("outerLoopIterationCount", iterationCount)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
        if (withLog) {
            logger.error(added.getMessage());
        }
    }

    public static void reportDcLfSolverFailure(ReportNode reportNode, String errorMessage) {
        reportNode.newReportNode()
                .withMessageTemplate("dcLfFailure", "Failed to solve linear system for DC load flow: ${errorMessage}")
                .withUntypedValue("errorMessage", errorMessage)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportDcLfComplete(ReportNode reportNode, boolean succeeded, String outerloopStatus) {
        reportNode.newReportNode()
                .withMessageTemplate("dcLfComplete", "DC load flow completed (solverSuccess=${succeeded}, outerloopStatus=${outerloopStatus})")
                .withUntypedValue("succeeded", succeeded)
                .withUntypedValue("outerloopStatus", outerloopStatus)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseNotStarted(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseNotStarted", "${impactedGeneratorCount} generators were discarded from voltage control because not started")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseReactiveRangeIsTooSmall(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseReactiveRangeIsTooSmall", "${impactedGeneratorCount} generators have been discarded from voltage control because of a too small reactive range")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseTargetPIsOutsideActiveLimits(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseTargetPIsOutsideActiveLimits", "${impactedGeneratorCount} generators have been discarded from voltage control because targetP is outside active power limits")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseTargetVIsImplausible(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseTargetVIsImplausible", "${impactedGeneratorCount} generators have been discarded from voltage control because targetV is implausible")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseInconsistentControlledBus(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseInconsistentControlledBus", "${impactedGeneratorCount} generators have been discarded from voltage control because connected to the same bus but controlling the voltage of different buses")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseInconsistentTargetVoltages(ReportNode reportNode, int impactedGeneratorCount) {
        reportNode.newReportNode()
                .withMessageTemplate("generatorsDiscardedFromVoltageControlBecauseInconsistentTargetVoltages", "${impactedGeneratorCount} generators have been discarded from voltage control because connected to the same bus but having different target voltages")
                .withUntypedValue(IMPACTED_GENERATOR_COUNT, impactedGeneratorCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportTransformersDiscardedFromVoltageControlBecauseTargetVIsInconsistent(ReportNode reportNode, int impactedTransformerCount) {
        reportNode.newReportNode()
                .withMessageTemplate("transformersDiscardedFromVoltageControlBecauseTargetVIsInconsistent", "${impactedTransformerCount} transformers have been discarded from voltage control because targetV is inconsistent")
                .withUntypedValue(IMPACTED_TRANSFORMER_COUNT, impactedTransformerCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportShuntsDiscardedFromVoltageControlBecauseTargetVIsInconsistent(ReportNode reportNode, int impactedShuntCount) {
        reportNode.newReportNode()
                .withMessageTemplate("shuntsDiscardedFromVoltageControlBecauseTargetVIsInconsistent", "${impactedShuntCount} shunt compensators have been discarded from voltage control because targetV is inconsistent")
                .withUntypedValue(IMPACTED_SHUNT_COUNT, impactedShuntCount)
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportAcLfComplete(ReportNode reportNode, boolean success, String solverStatus, String outerloopStatus) {
        TypedValue severity = success ? TypedValue.INFO_SEVERITY : TypedValue.ERROR_SEVERITY;
        String successText = success ? "successfully" : "with error";
        reportNode.newReportNode()
                .withMessageTemplate("acLfComplete", "AC load flow completed ${successText} (solverStatus=${solverStatus}, outerloopStatus=${outerloopStatus})")
                .withUntypedValue("successText", successText)
                .withUntypedValue("solverStatus", solverStatus)
                .withUntypedValue("outerloopStatus", outerloopStatus)
                .withSeverity(severity)
                .add();
    }

    public static ReportNode createLoadFlowReporter(ReportNode reportNode, String networkId) {
        return reportNode.newReportNode().withMessageTemplate("loadFlow", "Load flow on network '${networkId}'")
                .withUntypedValue(NETWORK_ID, networkId)
                .add();
    }

    public static ReportNode createRootLfNetworkReportNode(int networkNumCc, int networkNumSc) {
        return ReportNode.newRootReportNode()
                .withMessageTemplate("lfNetwork", "Network CC${networkNumCc} SC${networkNumSc}")
                .withUntypedValue(NETWORK_NUM_CC, networkNumCc)
                .withUntypedValue(NETWORK_NUM_SC, networkNumSc)
                .build();
    }

    public static ReportNode includeLfNetworkReportNode(ReportNode reportNode, ReportNode lfNetworkReportNode) {
        reportNode.include(lfNetworkReportNode);
        return lfNetworkReportNode;
    }

    public static ReportNode createNetworkInfoReporter(ReportNode reportNode) {
        return reportNode.newReportNode()
                .withMessageTemplate("networkInfo", "Network info")
                .add();
    }

    public static ReportNode createOuterLoopReporter(ReportNode reportNode, String outerLoopType) {
        return reportNode.newReportNode()
                .withMessageTemplate("OuterLoop", "Outer loop ${outerLoopType}")
                .withUntypedValue("outerLoopType", outerLoopType)
                .add();
    }

    public static ReportNode createOuterLoopIterationReporter(ReportNode reportNode, int outerLoopIteration) {
        return reportNode.newReportNode()
                .withMessageTemplate("OuterLoopIteration", "Outer loop iteration ${outerLoopIteration}")
                .withUntypedValue("outerLoopIteration", outerLoopIteration)
                .add();
    }

    public static ReportNode createSensitivityAnalysis(ReportNode reportNode, String networkId) {
        return reportNode.newReportNode()
                .withMessageTemplate("sensitivityAnalysis", "Sensitivity analysis on network '${networkId}'")
                .withUntypedValue(NETWORK_ID, networkId)
                .add();
    }

    public static ReportNode createAcSecurityAnalysis(ReportNode reportNode, String networkId) {
        return reportNode.newReportNode()
                .withMessageTemplate("acSecurityAnalysis", "AC security analysis on network '${networkId}'")
                .withUntypedValue(NETWORK_ID, networkId)
                .add();
    }

    public static ReportNode createDcSecurityAnalysis(ReportNode reportNode, String networkId) {
        return reportNode.newReportNode()
                .withMessageTemplate("dcSecurityAnalysis", "DC security analysis on network '${networkId}'")
                .withUntypedValue(NETWORK_ID, networkId)
                .add();
    }

    public static ReportNode createWoodburyDcSecurityAnalysis(ReportNode reportNode, String networkId) {
        return reportNode.newReportNode()
                .withMessageTemplate("woodburyDcSecurityAnalysis", "Woodbury DC security analysis on network '${networkId}'")
                .withUntypedValue(NETWORK_ID, networkId)
                .add();
    }

    public static ReportNode createPreContingencySimulation(ReportNode reportNode) {
        return reportNode.newReportNode()
                .withMessageTemplate("preContingencySimulation", "Pre-contingency simulation")
                .add();
    }

    public static ReportNode createPostContingencySimulation(ReportNode reportNode, String contingencyId) {
        return reportNode.newReportNode()
                .withMessageTemplate("postContingencySimulation", "Post-contingency simulation '${contingencyId}'")
                .withUntypedValue("contingencyId", contingencyId)
                .add();
    }

    public static ReportNode createOperatorStrategySimulation(ReportNode reportNode, String operatorStrategyId) {
        return reportNode.newReportNode()
                .withMessageTemplate("operatorStrategySimulation", "Operator strategy simulation '${operatorStrategyId}'")
                .withUntypedValue("operatorStrategyId", operatorStrategyId)
                .add();
    }

    public static ReportNode createDetailedSolverReporter(ReportNode reportNode, String solverName, int networkNumCc, int networkNumSc) {
        ReportNode subReportNode = reportNode.newReportNode()
                .withMessageTemplate("solver", solverName + " on Network CC${networkNumCc} SC${networkNumSc}")
                .withUntypedValue(NETWORK_NUM_CC, networkNumCc)
                .withUntypedValue(NETWORK_NUM_SC, networkNumSc)
                .add();
        subReportNode.newReportNode()
                .withMessageTemplate("solverNoOuterLoops", "No outer loops have been launched")
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
        return subReportNode;
    }

    public static ReportNode createDetailedSolverReporterOuterLoop(ReportNode reportNode, String solverName, int networkNumCc, int networkNumSc,
                                                                   int outerLoopIteration, String outerLoopType) {
        ReportNode subReportNode = reportNode.newReportNode()
                .withMessageTemplate("solver", solverName + " on Network CC${networkNumCc} SC${networkNumSc}")
                .withUntypedValue(NETWORK_NUM_CC, networkNumCc)
                .withUntypedValue(NETWORK_NUM_SC, networkNumSc)
                .add();
        subReportNode.newReportNode()
                .withMessageTemplate("solverOuterLoopCurrentType", "Newton-Raphson of outer loop iteration ${outerLoopIteration} of type ${outerLoopType}")
                .withUntypedValue("outerLoopIteration", outerLoopIteration)
                .withUntypedValue("outerLoopType", outerLoopType)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
        return subReportNode;
    }

    public static ReportNode createNewtonRaphsonMismatchReporter(ReportNode reportNode, int iteration) {
        if (iteration == 0) {
            return reportNode.newReportNode()
                    .withMessageTemplate("mismatchInitial", "Initial mismatch").
                    add();
        } else {
            return reportNode.newReportNode()
                    .withMessageTemplate("mismatchIteration", "Iteration ${iteration} mismatch")
                    .withUntypedValue(ITERATION, iteration)
                    .add();
        }
    }

    public static void reportNewtonRaphsonError(ReportNode reportNode, String error) {
        reportNode.newReportNode()
                .withMessageTemplate("NRError", "Newton Raphson error: ${error}")
                .withUntypedValue("error", error)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();
    }

    public static void reportNewtonRaphsonNorm(ReportNode reportNode, double norm) {
        reportNode.newReportNode()
                .withMessageTemplate("NRNorm", "Newton-Raphson norm |f(x)|=${norm}")
                .withUntypedValue("norm", norm)
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
    }

    public static void reportNewtonRaphsonLargestMismatches(ReportNode reportNode, String acEquationType, BusReport busReport) {
        String mismatchUnit;
        double mismatchUnitConverter;
        switch (acEquationType) {
            case "P" -> {
                mismatchUnit = "MW";
                mismatchUnitConverter = PerUnit.SB;
            }
            case "Q" -> {
                mismatchUnit = "MVar";
                mismatchUnitConverter = PerUnit.SB;
            }
            default -> {
                mismatchUnit = "p.u.";
                mismatchUnitConverter = 1.0;
            }
        }

        ReportNode subReportNode = reportNode.newReportNode()
                .withMessageTemplate("NRMismatch", "Largest ${equationType} mismatch: ${mismatch} ${mismatchUnit}")
                .withUntypedValue("equationType", acEquationType)
                .withTypedValue(MISMATCH, mismatchUnitConverter * busReport.mismatch(), OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withUntypedValue("mismatchUnit", mismatchUnit)
                .add();

        subReportNode.newReportNode()
                .withMessageTemplate("NRMismatchBusInfo", "Bus Id: ${busId} (nominalVoltage=${busNominalV}kV)")
                .withUntypedValue(BUS_ID, busReport.busId())
                .withUntypedValue("busNominalV", busReport.nominalV())
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();

        subReportNode.newReportNode()
                .withMessageTemplate("NRMismatchBusV", "Bus V: ${busV} pu, ${busPhi} rad")
                .withUntypedValue("busV", busReport.v())
                .withUntypedValue("busPhi", busReport.phi())
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();

        subReportNode.newReportNode()
                .withMessageTemplate("NRMismatchBusInjection", "Bus injection: ${busP} MW, ${busQ} MVar")
                .withUntypedValue("busP", busReport.p())
                .withUntypedValue("busQ", busReport.q())
                .withSeverity(TypedValue.TRACE_SEVERITY)
                .add();
    }

    public static void reportLineSearchStateVectorScaling(ReportNode reportNode, double stepSize) {
        reportNode.newReportNode()
                .withMessageTemplate("lineSearchStateVectorScaling", "Step size: ${stepSize} (line search)")
                .withUntypedValue("stepSize", stepSize)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportMaxVoltageChangeStateVectorScaling(ReportNode reportNode, double stepSize, int vCutCount, int phiCutCount) {
        reportNode.newReportNode()
                .withMessageTemplate("maxVoltageChangeStateVectorScaling", "Step size: ${stepSize} (max voltage change: ${vCutCount} Vmagnitude and ${phiCutCount} Vangle changes outside configured thresholds)")
                .withUntypedValue("stepSize", stepSize)
                .withUntypedValue("vCutCount", vCutCount)
                .withUntypedValue("phiCutCount", phiCutCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }

    public static void reportNewtonRaphsonBusesOutOfRealisticVoltageRange(ReportNode reportNode, Map<String, Double> busesOutOfRealisticVoltageRange, double minRealisticVoltage, double maxRealisticVoltage) {
        ReportNode voltageOutOfRangeReport = reportNode.newReportNode()
                .withMessageTemplate("newtonRaphsonBusesOutOfRealisticVoltageRange", "${busCountOutOfRealisticVoltageRange} buses have a voltage magnitude out of the configured realistic range [${minRealisticVoltage}, ${maxRealisticVoltage}] p.u.")
                .withUntypedValue("busCountOutOfRealisticVoltageRange", busesOutOfRealisticVoltageRange.size())
                .withUntypedValue("minRealisticVoltage", minRealisticVoltage)
                .withUntypedValue("maxRealisticVoltage", maxRealisticVoltage)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .add();

        busesOutOfRealisticVoltageRange.forEach((id, voltage) -> voltageOutOfRangeReport.newReportNode()
            .withMessageTemplate("newtonRaphsonBusesOutOfRealisticVoltageRangeDetails", "Bus ${busId} has an unrealistic voltage magnitude: ${voltage} p.u.")
            .withUntypedValue(BUS_ID, id)
            .withUntypedValue("voltage", voltage)
            .withSeverity(TypedValue.TRACE_SEVERITY)
            .add());
    }

    public static void reportAngleReferenceBusAndSlackBuses(ReportNode reportNode, String referenceBus, List<String> slackBuses) {
        reportNode.newReportNode()
                .withMessageTemplate("angleReferenceBusSelection", "Angle reference bus: ${referenceBus}")
                .withUntypedValue("referenceBus", referenceBus)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
        slackBuses.forEach(slackBus -> reportNode.newReportNode()
                .withMessageTemplate("slackBusSelection", "Slack bus: ${slackBus}")
                .withUntypedValue("slackBus", slackBus)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add());
    }

    public static void reportAcEmulationDisabledInWoodburyDcSecurityAnalysis(ReportNode reportNode) {
        reportNode.newReportNode()
                .withMessageTemplate("acEmulationDisabledInWoodburyDcSecurityAnalysis", "AC emulation of HVDC lines is disabled with Woodbury DC Security Analysis. HVDC active power setpoint will be used instead.")
                .withSeverity(TypedValue.WARN_SEVERITY)
                .add();
    }

    public static void reportContingencyActivePowerLossDistribution(ReportNode reportNode, double mismatch, double remaining) {
        reportNode.newReportNode()
                .withMessageTemplate("contingencyActivePowerLossDistribution", "Contingency caused the loss of ${mismatch} MW injection: ${distributed} MW distributed, ${remaining} MW remaining.")
                .withUntypedValue(MISMATCH, mismatch)
                .withUntypedValue("distributed", mismatch - remaining)
                .withUntypedValue("remaining", remaining)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .add();
    }
}
