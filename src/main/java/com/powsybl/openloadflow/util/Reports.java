/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.util;

import com.powsybl.commons.reporter.Report;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.commons.reporter.TypedValue;
import com.powsybl.openloadflow.OpenLoadFlowReportConstants;

import java.util.Map;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public final class Reports {

    private Reports() {
    }

    public static void reportNetworkSize(Reporter reporter, int networkNumCc, int networkNumSc, int busCount, int branchCount) {
        reporter.report(Report.builder()
                .withKey("networkSize")
                .withDefaultMessage("Network CC${networkNumCc} SC${networkNumSc} has ${busCount} buses and ${branchCount} branches")
                .withValue("networkNumCc", networkNumCc)
                .withValue("networkNumSc", networkNumSc)
                .withValue("busCount", busCount)
                .withValue("branchCount", branchCount)
                .build());
    }

    public static void reportNetworkBalance(Reporter reporter, int networkNumCc, int networkNumSc, double activeGeneration, double activeLoad, double reactiveGeneration, double reactiveLoad) {
        reporter.report(Report.builder()
                .withKey("networkBalance")
                .withDefaultMessage("Network CC${networkNumCc} SC${networkNumSc} balance: active generation=${activeGeneration} MW, active load=${activeLoad} MW, reactive generation=${reactiveGeneration} MVar, reactive load=${reactiveLoad} MVar")
                .withValue("networkNumCc", networkNumCc)
                .withValue("networkNumSc", networkNumSc)
                .withValue("activeGeneration", activeGeneration)
                .withValue("activeLoad", activeLoad)
                .withValue("reactiveGeneration", reactiveGeneration)
                .withValue("reactiveLoad", reactiveLoad)
                .build());
    }

    public static void reportNetworkMustHaveAtLeastOneBusVoltageControlled(Reporter reporter, int networkNumCc, int networkNumSc) {
        reporter.report(Report.builder()
                .withKey("networkMustHaveAtLeastOneBusVoltageControlled")
                .withDefaultMessage("Network CC${networkNumCc} SC${networkNumSc} must have at least one bus voltage controlled")
                .withValue("networkNumCc", networkNumCc)
                .withValue("networkNumSc", networkNumSc)
                .build());
    }

    public static void reportMismatchDistributionFailure(Reporter reporter, int iteration, double remainingMismatch) {
        reporter.report(Report.builder()
                .withKey("mismatchDistributionFailure")
                .withDefaultMessage("Iteration ${iteration}: failed to distribute slack bus active power mismatch, ${mismatch} MW remains")
                .withValue("iteration", iteration)
                .withTypedValue("mismatch", remainingMismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .build());
    }

    public static void reportMismatchDistributionSuccess(Reporter reporter, int iteration, double slackBusActivePowerMismatch, int iterationCount) {
        reporter.report(Report.builder()
                .withKey("mismatchDistributionSuccess")
                .withDefaultMessage("Iteration ${iteration}: slack bus active power (${initialMismatch} MW) distributed in ${iterationCount} iterations")
                .withValue("iteration", iteration)
                .withTypedValue("initialMismatch", slackBusActivePowerMismatch, OpenLoadFlowReportConstants.MISMATCH_TYPED_VALUE)
                .withValue("iterationCount", iterationCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .build());
    }

    public static void reportNoMismatchDistribution(Reporter reporter, int iteration) {
        reporter.report(Report.builder()
                .withKey("NoMismatchDistribution")
                .withDefaultMessage("Iteration ${iteration}: already balanced")
                .withValue("iteration", iteration)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .build());
    }

    public static void reportPvToPqBuses(Reporter reporter, int pvToPqBusCount, int remainingPvBusCount) {
        reporter.report(Report.builder()
                .withKey("switchPvPq")
                .withDefaultMessage("${pvToPqBusCount} buses switched PV -> PQ ({remainingPvBusCount} bus remains PV}")
                .withValue("pvToPqBusCount", pvToPqBusCount)
                .withValue("remainingPvBusCount", remainingPvBusCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .build());
    }

    public static void reportPqToPvBuses(Reporter reporter, int pqToPvBusCount, int blockedPqBusCount) {
        reporter.report(Report.builder()
                .withKey("switchPqPv")
                .withDefaultMessage("${pqToPvBusCount} buses switched PQ -> PV ({blockedPqBusCount} buses blocked PQ because have reach max number of switch)")
                .withValue("pqToPvBusCount", pqToPvBusCount)
                .withValue("blockedPqBusCount", blockedPqBusCount)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .build());
    }

    public static void reportDcLfSolverFailure(Reporter reporter, String errorMessage) {
        reporter.report(Report.builder()
                .withKey("dcLfFailure")
                .withDefaultMessage("Failed to solve linear system for DC load flow: ${errorMessage}")
                .withValue("errorMessage", errorMessage)
                .withSeverity(TypedValue.ERROR_SEVERITY)
                .build());
    }

    public static void reportDcLfComplete(Reporter reporter, String lfStatus) {
        reporter.report(Report.builder()
                .withKey("dcLfComplete")
                .withDefaultMessage("DC load flow completed (status=${lfStatus})")
                .withValue("lfStatus", lfStatus)
                .withSeverity(TypedValue.INFO_SEVERITY)
                .build());
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseNotStarted(Reporter reporter, int impactedGeneratorCount) {
        reporter.report(Report.builder()
                .withKey("generatorsDiscardedFromVoltageControlBecauseNotStarted")
                .withDefaultMessage("${impactedGeneratorCount} generators have been discarded from voltage control because not started")
                .withValue("impactedGeneratorCount", impactedGeneratorCount)
                .build());
    }

    public static void reportGeneratorsDiscardedFromVoltageControlBecauseMaxReactiveRangeIsTooSmall(Reporter reporter, int impactedGeneratorCount) {
        reporter.report(Report.builder()
                .withKey("generatorsDiscardedFromVoltageControlBecauseMaxReactiveRangeIsTooSmall")
                .withDefaultMessage("${impactedGeneratorCount} generators have been discarded from voltage control because of a too small max reactive range")
                .withValue("impactedGeneratorCount", impactedGeneratorCount)
                .build());
    }

    public static Reporter createLoadFlowReporter(Reporter reporter, String networkId) {
        return reporter.createSubReporter("loadFlow", "Load flow on network ${networkId}",
                "networkId", networkId);
    }

    public static Reporter createLfNetworkReporter(Reporter reporter, int networkNumCc, int networkNumSc) {
        return reporter.createSubReporter("lfNetwork", "Network CC${networkNumCc} SC${networkNumSc}",
                Map.of("networkNumCc", new TypedValue(networkNumCc, TypedValue.UNTYPED),
                       "networkNumSc", new TypedValue(networkNumSc, TypedValue.UNTYPED)));
    }

    public static Reporter createPostLoadingProcessingReporter(Reporter reporter) {
        return reporter.createSubReporter("postLoadingProcessing", "Post loading processing");
    }

    public static Reporter createOuterLoopReporter(Reporter reporter, String outerLoopType) {
        return reporter.createSubReporter("OuterLoop", "Outer loop ${outerLoopType}", "outerLoopType", outerLoopType);
    }

    public static Reporter createSensitivityAnalysis(Reporter reporter, String networkId) {
        return reporter.createSubReporter("sensitivityAnalysis",
                "Sensitivity analysis on network ${networkId}", "networkId", networkId);
    }
}
