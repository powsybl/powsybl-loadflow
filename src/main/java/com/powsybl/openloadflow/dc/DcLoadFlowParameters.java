/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DcLoadFlowParameters extends AbstractLoadFlowParameters<DcLoadFlowParameters> {

    private DcEquationSystemCreationParameters equationSystemCreationParameters = new DcEquationSystemCreationParameters();

    private boolean distributedSlack = LoadFlowParameters.DEFAULT_DISTRIBUTED_SLACK;

    private LoadFlowParameters.BalanceType balanceType = LoadFlowParameters.DEFAULT_BALANCE_TYPE;

    private boolean setVToNan = false;

    protected List<DcOuterLoop> outerLoops = Collections.emptyList();

    private int maxOuterLoopIterations = DEFAULT_MAX_OUTER_LOOP_ITERATIONS;

    private double slackBusPMaxMismatch = OpenLoadFlowParameters.SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE;

    private double areaInterchangePMaxMismatch = OpenLoadFlowParameters.AREA_INTERCHANGE_P_MAX_MISMATCH_DEFAULT_VALUE;

    public DcEquationSystemCreationParameters getEquationSystemCreationParameters() {
        return equationSystemCreationParameters;
    }

    public DcLoadFlowParameters setEquationSystemCreationParameters(DcEquationSystemCreationParameters equationSystemCreationParameters) {
        this.equationSystemCreationParameters = Objects.requireNonNull(equationSystemCreationParameters);
        return this;
    }

    public int getMaxOuterLoopIterations() {
        return maxOuterLoopIterations;
    }

    public DcLoadFlowParameters setMaxOuterLoopIterations(int maxOuterLoopIterations) {
        this.maxOuterLoopIterations = maxOuterLoopIterations;
        return this;
    }

    public boolean isDistributedSlack() {
        return distributedSlack;
    }

    public DcLoadFlowParameters setDistributedSlack(boolean distributedSlack) {
        this.distributedSlack = distributedSlack;
        return this;
    }

    public LoadFlowParameters.BalanceType getBalanceType() {
        return balanceType;
    }

    public DcLoadFlowParameters setBalanceType(LoadFlowParameters.BalanceType balanceType) {
        this.balanceType = Objects.requireNonNull(balanceType);
        return this;
    }

    public boolean isSetVToNan() {
        return setVToNan;
    }

    public DcLoadFlowParameters setSetVToNan(boolean setVToNan) {
        this.setVToNan = setVToNan;
        return this;
    }

    public List<DcOuterLoop> getOuterLoops() {
        return outerLoops;
    }

    public DcLoadFlowParameters setOuterLoops(List<DcOuterLoop> outerLoops) {
        this.outerLoops = Objects.requireNonNull(outerLoops);
        return this;
    }

    public double getSlackBusPMaxMismatch() {
        return slackBusPMaxMismatch;
    }

    public DcLoadFlowParameters setSlackBusPMaxMismatch(double slackBusPMaxMismatch) {
        this.slackBusPMaxMismatch = slackBusPMaxMismatch;
        return this;
    }

    public double getAreaInterchangePMaxMismatch() {
        return areaInterchangePMaxMismatch;
    }

    public DcLoadFlowParameters setAreaInterchangePMaxMismatch(double areaInterchangePMaxMismatch) {
        this.areaInterchangePMaxMismatch = areaInterchangePMaxMismatch;
        return this;
    }

    @Override
    public String toString() {
        return "DcLoadFlowParameters(" +
                "networkParameters=" + networkParameters +
                ", equationSystemCreationParameters=" + equationSystemCreationParameters +
                ", matrixFactory=" + matrixFactory.getClass().getSimpleName() +
                ", distributedSlack=" + distributedSlack +
                ", balanceType=" + balanceType +
                ", setVToNan=" + setVToNan +
                ", maxOuterLoopIterations=" + maxOuterLoopIterations +
                ')';
    }
}
