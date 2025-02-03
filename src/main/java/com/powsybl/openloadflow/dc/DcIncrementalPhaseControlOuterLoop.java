/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.lf.outerloop.AbstractIncrementalPhaseControlOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.IncrementalContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.TransformerPhaseControl;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DcIncrementalPhaseControlOuterLoop
        extends AbstractIncrementalPhaseControlOuterLoop<DcVariableType, DcEquationType, DcLoadFlowParameters, DcLoadFlowContext, DcOuterLoopContext> implements DcOuterLoop {

    public DcIncrementalPhaseControlOuterLoop() {
        super(LoggerFactory.getLogger(DcIncrementalPhaseControlOuterLoop.class));
    }

    public static class DcSensitivityContext extends AbstractSensitivityContext<DcVariableType, DcEquationType> {
        public DcSensitivityContext(LfNetwork network, List<LfBranch> controllerBranches, EquationSystem<DcVariableType, DcEquationType> equationSystem, JacobianMatrix<DcVariableType, DcEquationType> jacobianMatrix) {
            super(network, controllerBranches, equationSystem, jacobianMatrix);
        }

        public DenseMatrix calculateSensitivityValues(List<LfBranch> controllerBranches, int[] controllerBranchIndex,
                                                      EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                                      JacobianMatrix<DcVariableType, DcEquationType> jacobianMatrix) {
            DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), controllerBranches.size());
            for (LfBranch controllerBranch : controllerBranches) {
                equationSystem.getEquation(controllerBranch.getNum(), DcEquationType.BRANCH_TARGET_ALPHA1)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), controllerBranchIndex[controllerBranch.getNum()], Math.toRadians(1d)));
            }
            jacobianMatrix.solveTransposed(rhs);
            return rhs;
        }
    }

    @Override
    public OuterLoopResult check(DcOuterLoopContext context, ReportNode reportNode) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        var contextData = (IncrementalContextData) context.getData();

        LfNetwork network = context.getNetwork();

        List<LfBranch> controllerBranches = getControllerBranches(network);

        // find list of phase controls that are in active power control
        List<TransformerPhaseControl> activePowerControlPhaseControls = new ArrayList<>();
        for (LfBranch controllerBranch : controllerBranches) {
            controllerBranch.getPhaseControl().ifPresent(phaseControl -> {
                if (phaseControl.getMode() == TransformerPhaseControl.Mode.CONTROLLER) {
                    activePowerControlPhaseControls.add(phaseControl);
                }
            });
        }

        if (!activePowerControlPhaseControls.isEmpty()) {
            var sensitivityContext = new DcSensitivityContext(network,
                    controllerBranches,
                    context.getLoadFlowContext().getEquationSystem(),
                    context.getLoadFlowContext().getJacobianMatrix());

            if (checkActivePowerControlPhaseControls(sensitivityContext,
                    contextData,
                    activePowerControlPhaseControls) != 0) {
                status = OuterLoopStatus.UNSTABLE;
            }
        }

        return new OuterLoopResult(this, status);
    }
}
