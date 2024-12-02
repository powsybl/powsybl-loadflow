/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.outerloop.AcOuterLoop;
import com.powsybl.openloadflow.ac.solver.AcSolverFactory;
import com.powsybl.openloadflow.ac.solver.NewtonKrylovParameters;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonFactory;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonParameters;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcLoadFlowParameters extends AbstractLoadFlowParameters<AcLoadFlowParameters> {

    private AcEquationSystemCreationParameters equationSystemCreationParameters = new AcEquationSystemCreationParameters();

    private NewtonRaphsonParameters newtonRaphsonParameters = new NewtonRaphsonParameters();

    private NewtonKrylovParameters newtonKrylovParameters = new NewtonKrylovParameters();

    private List<AcOuterLoop> outerLoops = Collections.emptyList();

    private int maxOuterLoopIterations = DEFAULT_MAX_OUTER_LOOP_ITERATIONS;

    private VoltageInitializer voltageInitializer = new UniformValueVoltageInitializer();

    private boolean asymmetrical = LfNetworkParameters.ASYMMETRICAL_DEFAULT_VALUE;

    public static final boolean FIX_REMOTE_VOLTAGE_TARGET_DEFAULT_VALUE = false;

    private OpenLoadFlowParameters.SlackDistributionFailureBehavior slackDistributionFailureBehavior = OpenLoadFlowParameters.SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS;

    private AcSolverFactory solverFactory = new NewtonRaphsonFactory();

    private boolean detailedReport = false;

    private boolean fixRemoteVoltageTarget = FIX_REMOTE_VOLTAGE_TARGET_DEFAULT_VALUE;

    public AcEquationSystemCreationParameters getEquationSystemCreationParameters() {
        return equationSystemCreationParameters;
    }

    public AcLoadFlowParameters setEquationSystemCreationParameters(AcEquationSystemCreationParameters equationSystemCreationParameters) {
        this.equationSystemCreationParameters = Objects.requireNonNull(equationSystemCreationParameters);
        return this;
    }

    public NewtonRaphsonParameters getNewtonRaphsonParameters() {
        return newtonRaphsonParameters;
    }

    public AcLoadFlowParameters setNewtonRaphsonParameters(NewtonRaphsonParameters newtonRaphsonParameters) {
        this.newtonRaphsonParameters = Objects.requireNonNull(newtonRaphsonParameters);
        return this;
    }

    public NewtonKrylovParameters getNewtonKrylovParameters() {
        return newtonKrylovParameters;
    }

    public AcLoadFlowParameters setNewtonKrylovParameters(NewtonKrylovParameters newtonKrylovParameters) {
        this.newtonKrylovParameters = Objects.requireNonNull(newtonKrylovParameters);
        return this;
    }

    public List<AcOuterLoop> getOuterLoops() {
        return outerLoops;
    }

    public AcLoadFlowParameters setOuterLoops(List<AcOuterLoop> outerLoops) {
        this.outerLoops = Objects.requireNonNull(outerLoops);
        return this;
    }

    public int getMaxOuterLoopIterations() {
        return maxOuterLoopIterations;
    }

    public AcLoadFlowParameters setMaxOuterLoopIterations(int maxOuterLoopIterations) {
        this.maxOuterLoopIterations = maxOuterLoopIterations;
        return this;
    }

    public VoltageInitializer getVoltageInitializer() {
        return voltageInitializer;
    }

    public AcLoadFlowParameters setVoltageInitializer(VoltageInitializer voltageInitializer) {
        this.voltageInitializer = Objects.requireNonNull(voltageInitializer);
        return this;
    }

    public boolean isAsymmetrical() {
        return asymmetrical;
    }

    public AcLoadFlowParameters setAsymmetrical(boolean asymmetrical) {
        this.asymmetrical = asymmetrical;
        return this;
    }

    public OpenLoadFlowParameters.SlackDistributionFailureBehavior getSlackDistributionFailureBehavior() {
        return slackDistributionFailureBehavior;
    }

    public AcLoadFlowParameters setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior slackDistributionFailureBehavior) {
        this.slackDistributionFailureBehavior = Objects.requireNonNull(slackDistributionFailureBehavior);
        return this;
    }

    public AcSolverFactory getSolverFactory() {
        return solverFactory;
    }

    public AcLoadFlowParameters setSolverFactory(AcSolverFactory solverFactory) {
        this.solverFactory = Objects.requireNonNull(solverFactory);
        return this;
    }

    public boolean isDetailedReport() {
        return detailedReport;
    }

    public AcLoadFlowParameters setDetailedReport(boolean detailedReport) {
        this.detailedReport = detailedReport;
        return this;
    }

    public boolean isFixRemoteVoltageTarget() {
        return fixRemoteVoltageTarget;
    }

    public AcLoadFlowParameters setFixRemoteVoltageTarget(boolean fixRemoteVoltageTarget) {
        this.fixRemoteVoltageTarget = fixRemoteVoltageTarget;
        return this;
    }

    @Override
    public String toString() {
        return "AcLoadFlowParameters(" +
                "networkParameters=" + networkParameters +
                ", equationSystemCreationParameters=" + equationSystemCreationParameters +
                ", newtonRaphsonParameters=" + newtonRaphsonParameters +
                ", newtonKrylovParameters=" + newtonKrylovParameters +
                ", outerLoops=" + outerLoops.stream().map(outerLoop -> outerLoop.getClass().getSimpleName()).toList() +
                ", maxOuterLoopIterations=" + maxOuterLoopIterations +
                ", matrixFactory=" + matrixFactory.getClass().getSimpleName() +
                ", voltageInitializer=" + voltageInitializer.getClass().getSimpleName() +
                ", asymmetrical=" + asymmetrical +
                ", slackDistributionFailureBehavior=" + slackDistributionFailureBehavior.name() +
                ", solverFactory=" + solverFactory.getClass().getSimpleName() +
                ", detailedReport=" + detailedReport +
                ", fixRemoteTargetVoltage=" + fixRemoteVoltageTarget +
                ')';
    }
}
