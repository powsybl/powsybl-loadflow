/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.lf.AbstractLoadFlowResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcLoadFlowResult extends AbstractLoadFlowResult {

    public static AcLoadFlowResult createNoCalculationResult(LfNetwork network) {
        return new AcLoadFlowResult(network, 0, 0, AcSolverStatus.NO_CALCULATION, OuterLoopResult.stable(), Double.NaN, Double.NaN,
                Collections.EMPTY_MAP);
    }

    private final int solverIterations;

    private final AcSolverStatus solverStatus;

    private final Map<Class<? extends AcOuterLoop>, Object> outerLoopInitData;

    public AcLoadFlowResult(LfNetwork network, int outerLoopIterations, int solverIterations,
                            AcSolverStatus solverStatus, OuterLoopResult outerLoopResult,
                            double slackBusActivePowerMismatch, double distributedActivePower,
                            Map<Class<? extends AcOuterLoop>, Object> outerLoopInitData) {
        super(network, slackBusActivePowerMismatch, outerLoopIterations, outerLoopResult, distributedActivePower);
        this.solverIterations = solverIterations;
        this.solverStatus = Objects.requireNonNull(solverStatus);
        this.outerLoopInitData = outerLoopInitData;
    }

    public int getSolverIterations() {
        return solverIterations;
    }

    public AcSolverStatus getSolverStatus() {
        return solverStatus;
    }

    @Override
    public boolean isSuccess() {
        return solverStatus == AcSolverStatus.CONVERGED && getOuterLoopResult().status() == OuterLoopStatus.STABLE;
    }

    public boolean isWithNetworkUpdate() {
        // do not reset state in case all results are ok and no NR iterations because it means that the network was
        // not changed and no calculation update was needed.
        return isSuccess() && solverIterations > 0;
    }

    @Override
    public Status toComponentResultStatus() {
        if (network.getValidity() == LfNetwork.Validity.INVALID_NO_GENERATOR) {
            return new Status(LoadFlowResult.ComponentResult.Status.NO_CALCULATION, network.getValidity().toString());
        } else if (network.getValidity() == LfNetwork.Validity.INVALID_NO_GENERATOR_VOLTAGE_CONTROL) {
            return new Status(LoadFlowResult.ComponentResult.Status.FAILED, network.getValidity().toString());
        }
        if (getOuterLoopResult().status() == OuterLoopStatus.UNSTABLE) {
            return new Status(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED, "Reached outer loop max iterations limit. Last outer loop name: " + getOuterLoopResult().outerLoopName());
        } else if (getOuterLoopResult().status() == OuterLoopStatus.FAILED) {
            return new Status(LoadFlowResult.ComponentResult.Status.FAILED, "Outer loop failed: " + getOuterLoopResult().statusText());
        } else {
            return switch (getSolverStatus()) {
                case CONVERGED -> new Status(LoadFlowResult.ComponentResult.Status.CONVERGED, "Converged");
                case MAX_ITERATION_REACHED -> new Status(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED, "Reached Newton-Raphson max iterations limit");
                case SOLVER_FAILED -> new Status(LoadFlowResult.ComponentResult.Status.FAILED, "Solver failed");
                case UNREALISTIC_STATE -> new Status(LoadFlowResult.ComponentResult.Status.FAILED, "Unrealistic state");
                case NO_CALCULATION -> new Status(LoadFlowResult.ComponentResult.Status.NO_CALCULATION, "No calculation");
            };
        }
    }

    public Map<Class<? extends AcOuterLoop>, Object> getOuterLoopInitData() {
        return outerLoopInitData;
    }

    @Override
    public String toString() {
        return "AcLoadFlowResult(outerLoopIterations=" + outerLoopIterations
                + ", newtonRaphsonIterations=" + solverIterations
                + ", solverStatus=" + solverStatus
                + ", outerLoopStatus=" + outerLoopResult.status()
                + ", slackBusActivePowerMismatch=" + slackBusActivePowerMismatch * PerUnit.SB
                + ", distributedActivePower=" + distributedActivePower * PerUnit.SB
                + ")";
    }
}
