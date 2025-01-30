/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.util.Reports;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.monitor.StateMonitor;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcSecurityAnalysis extends AbstractSecurityAnalysis<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcLoadFlowResult> {

    protected AcSecurityAnalysis(Network network, MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                 List<StateMonitor> stateMonitors, ReportNode reportNode) {
        super(network, matrixFactory, connectivityFactory, stateMonitors, reportNode);
    }

    @Override
    protected LoadFlowModel getLoadFlowModel() {
        return LoadFlowModel.AC;
    }

    @Override
    protected ReportNode createSaRootReportNode() {
        return Reports.createAcSecurityAnalysis(reportNode, network.getId());
    }

    @Override
    protected boolean isShuntCompensatorVoltageControlOn(LoadFlowParameters lfParameters) {
        return lfParameters.isShuntCompensatorVoltageControlOn();
    }

    @Override
    protected AcLoadFlowParameters createParameters(LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, boolean breakers) {
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, lfParameters, lfParametersExt, matrixFactory, connectivityFactory, breakers, false);
        if (acParameters.getNetworkParameters().getMaxSlackBusCount() > 1) {
            LOGGER.warn("Multiple slack buses in a security analysis is not supported, force to 1");
        }
        acParameters.getNetworkParameters()
                .setCacheEnabled(false) // force not caching as not supported in secu analysis
                .setReferenceBusSelector(ReferenceBusSelector.DEFAULT_SELECTOR) // not supported yet
                .setMaxSlackBusCount(1);
        acParameters.setDetailedReport(lfParametersExt.getReportedFeatures().contains(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_SECURITY_ANALYSIS));
        return acParameters;
    }

    @Override
    protected AcLoadFlowContext createLoadFlowContext(LfNetwork lfNetwork, AcLoadFlowParameters parameters) {
        return new AcLoadFlowContext(lfNetwork, parameters);
    }

    @Override
    protected AcloadFlowEngine createLoadFlowEngine(AcLoadFlowContext context) {
        return new AcloadFlowEngine(context);
    }

    @Override
    protected void afterPreContingencySimulation(AcLoadFlowParameters acParameters) {
        // in some post-contingency computation, it does not remain elements to participate to slack distribution.
        // in that case, no exception should be thrown. If parameters were configured to throw, reconfigure to FAIL.
        // (the contingency will be marked as not converged)
        if (OpenLoadFlowParameters.SlackDistributionFailureBehavior.THROW == acParameters.getSlackDistributionFailureBehavior()) {
            acParameters.setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.FAIL);
        }
    }

    public static PostContingencyComputationStatus postContingencyStatusFromAcLoadFlowResult(AcLoadFlowResult result) {
        if (result.getOuterLoopResult().status() == OuterLoopStatus.UNSTABLE) {
            return PostContingencyComputationStatus.MAX_ITERATION_REACHED;
        } else if (result.getOuterLoopResult().status() == OuterLoopStatus.FAILED) {
            return PostContingencyComputationStatus.FAILED;
        } else {
            return switch (result.getSolverStatus()) {
                case CONVERGED -> PostContingencyComputationStatus.CONVERGED;
                case MAX_ITERATION_REACHED -> PostContingencyComputationStatus.MAX_ITERATION_REACHED;
                case SOLVER_FAILED -> PostContingencyComputationStatus.SOLVER_FAILED;
                case NO_CALCULATION -> PostContingencyComputationStatus.NO_IMPACT;
                case UNREALISTIC_STATE -> PostContingencyComputationStatus.FAILED;
            };
        }
    }

    @Override
    protected PostContingencyComputationStatus postContingencyStatusFromLoadFlowResult(AcLoadFlowResult result) {
        return postContingencyStatusFromAcLoadFlowResult(result);
    }

    @Override
    protected void beforeActionLoadFlowRun(AcLoadFlowContext context) {
        context.getParameters().setVoltageInitializer(new PreviousValueVoltageInitializer(true));
    }

    @Override
    protected void updateContext(AcLoadFlowResult result, AcLoadFlowContext context) {
        context.setOuterLoopInitData(result.getOuterLoopInitData());
    }
}
