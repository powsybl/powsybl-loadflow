/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.lf.outerloop.AbstractIncrementalPhaseControlOuterLoop;
import com.powsybl.openloadflow.lf.outerloop.IncrementalContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcIncrementalPhaseControlOuterLoop
        extends AbstractIncrementalPhaseControlOuterLoop<AcVariableType, AcEquationType, AcLoadFlowParameters, AcLoadFlowContext, AcOuterLoopContext>
        implements AcOuterLoop {

    public AcIncrementalPhaseControlOuterLoop() {
        super(LoggerFactory.getLogger(AcIncrementalPhaseControlOuterLoop.class));
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        var contextData = new IncrementalContextData();
        context.setData(contextData);

        List<LfBranch> controllerBranches = getControllerBranches(context.getNetwork());
        for (LfBranch controllerBranch : controllerBranches) {
            contextData.getControllersContexts().put(controllerBranch.getId(), new IncrementalContextData.ControllerContext(MAX_DIRECTION_CHANGE));
        }

        fixPhaseShifterNecessaryForConnectivity(context.getNetwork(), controllerBranches);
    }

    public static class AcSensitivityContext extends AbstractSensitivityContext<AcVariableType, AcEquationType> {
        public AcSensitivityContext(LfNetwork network, List<LfBranch> controllerBranches, EquationSystem<AcVariableType, AcEquationType> equationSystem, JacobianMatrix<AcVariableType, AcEquationType> jacobianMatrix) {
            super(network, controllerBranches, equationSystem, jacobianMatrix);
        }

        public DenseMatrix calculateSensitivityValues(List<LfBranch> controllerBranches, int[] controllerBranchIndex,
                                                      EquationSystem<AcVariableType, AcEquationType> equationSystem,
                                                      JacobianMatrix<AcVariableType, AcEquationType> jacobianMatrix) {
            DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), controllerBranches.size());
            for (LfBranch controllerBranch : controllerBranches) {
                equationSystem.getEquation(controllerBranch.getNum(), AcEquationType.BRANCH_TARGET_ALPHA1)
                        .ifPresent(equation -> rhs.set(equation.getColumn(), controllerBranchIndex[controllerBranch.getNum()], Math.toRadians(1d)));
            }
            jacobianMatrix.solveTransposed(rhs);
            return rhs;
        }

        @SuppressWarnings("unchecked")
        private EquationTerm<AcVariableType, AcEquationType> getI1(LfBranch controlledBranch) {
            return (EquationTerm<AcVariableType, AcEquationType>) controlledBranch.getI1();
        }

        @SuppressWarnings("unchecked")
        private EquationTerm<AcVariableType, AcEquationType> getI2(LfBranch controlledBranch) {
            return (EquationTerm<AcVariableType, AcEquationType>) controlledBranch.getI2();
        }

        public double calculateSensitivityFromA2I(LfBranch controllerBranch, LfBranch controlledBranch, TwoSides controlledSide) {
            var i = controlledSide == TwoSides.ONE ? getI1(controlledBranch) : getI2(controlledBranch);
            return calculateSensitivityFromA2S(controllerBranch, i);
        }
    }

    private int checkCurrentLimiterPhaseControls(AcSensitivityContext sensitivityContext, IncrementalContextData contextData,
                                                     List<TransformerPhaseControl> currentLimiterPhaseControls) {
        MutableInt numOfCurrentLimiterPstsThatChangedTap = new MutableInt(0);

        for (TransformerPhaseControl phaseControl : currentLimiterPhaseControls) {
            LfBranch controllerBranch = phaseControl.getControllerBranch();
            LfBranch controlledBranch = phaseControl.getControlledBranch();
            double i = computeI(phaseControl);
            if (i > phaseControl.getTargetValue()) {
                var controllerContext = contextData.getControllersContexts().get(controllerBranch.getId());
                double di = phaseControl.getTargetValue() - i;
                double a2i = sensitivityContext.calculateSensitivityFromA2I(controllerBranch, controlledBranch, phaseControl.getControlledSide());
                if (Math.abs(a2i) > SENSI_EPS) {
                    double da = Math.toRadians(di / a2i);
                    double ib = computeIb(phaseControl);
                    logger.trace("Controlled branch '{}' current is {} A and above target value {} A, a phase shift of {}° is required",
                            controlledBranch.getId(), i * ib, phaseControl.getTargetValue() * ib, Math.toDegrees(da));
                    PiModel piModel = controllerBranch.getPiModel();

                    int oldTapPosition = piModel.getTapPosition();
                    double oldA1 = piModel.getA1();
                    Range<Integer> tapPositionRange = piModel.getTapPositionRange();
                    piModel.updateTapPositionToExceedNewA1(da, MAX_TAP_SHIFT, controllerContext.getAllowedDirection()).ifPresent(direction -> {
                        controllerContext.updateAllowedDirection(direction);
                        numOfCurrentLimiterPstsThatChangedTap.add(1);
                    });

                    if (piModel.getTapPosition() != oldTapPosition) {
                        logger.debug("Controller branch '{}' changed tap from {} to {} to limit current (full range: {})", controllerBranch.getId(),
                                oldTapPosition, piModel.getTapPosition(), tapPositionRange);

                        double discreteDa = piModel.getA1() - oldA1;
                        checkImpactOnOtherPhaseShifters(sensitivityContext, phaseControl, currentLimiterPhaseControls, discreteDa);
                    }
                }
            }
        }
        return numOfCurrentLimiterPstsThatChangedTap.getValue();
    }

    private void checkImpactOnOtherPhaseShifters(AcSensitivityContext sensitivityContext, TransformerPhaseControl phaseControl,
                                                 List<TransformerPhaseControl> currentLimiterPhaseControls, double da) {
        LfBranch controllerBranch = phaseControl.getControllerBranch();
        for (TransformerPhaseControl otherPhaseControl : currentLimiterPhaseControls) {
            if (otherPhaseControl != phaseControl) {
                LfBranch otherControlledBranch = otherPhaseControl.getControlledBranch();
                double i = computeI(otherPhaseControl);
                if (i > otherPhaseControl.getTargetValue()) {
                    // get cross sensitivity of the phase shifter controller branch on the other phase shifter controlled branch
                    double crossA2i = sensitivityContext.calculateSensitivityFromA2I(controllerBranch, otherControlledBranch,
                            otherPhaseControl.getControlledSide());
                    double ib = computeIb(otherPhaseControl);
                    double di = Math.toDegrees(da) * crossA2i;
                    if (di > PHASE_SHIFT_CROSS_IMPACT_MARGIN * (i - otherPhaseControl.getTargetValue())) {
                        logger.warn("Controller branch '{}' tap change significantly impact (≈ {} A) another phase shifter current also above its limit '{}', simulation might not be reliable",
                                controllerBranch.getId(), di * ib, otherPhaseControl.getControlledBranch().getId());
                    }
                }
            }
        }
    }

    private static double computeIb(TransformerPhaseControl phaseControl) {
        LfBus bus = phaseControl.getControlledSide() == TwoSides.ONE
                ? phaseControl.getControlledBranch().getBus1() : phaseControl.getControlledBranch().getBus2();
        return PerUnit.ib(bus.getNominalV());
    }

    private static double computeI(TransformerPhaseControl phaseControl) {
        var i = phaseControl.getControlledSide() == TwoSides.ONE
                ? phaseControl.getControlledBranch().getI1() : phaseControl.getControlledBranch().getI2();
        return i.eval();
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {

        var contextData = (IncrementalContextData) context.getData();

        LfNetwork network = context.getNetwork();

        List<LfBranch> controllerBranches = getControllerBranches(network);

        // find list of phase controls that are in current limiter and active power control
        List<TransformerPhaseControl> activePowerControlPhaseControls = new ArrayList<>();
        List<TransformerPhaseControl> currentLimiterPhaseControls = new ArrayList<>();
        for (LfBranch controllerBranch : controllerBranches) {
            controllerBranch.getPhaseControl().ifPresent(phaseControl -> {
                switch (phaseControl.getMode()) {
                    case CONTROLLER:
                        activePowerControlPhaseControls.add(phaseControl);
                        break;
                    case LIMITER:
                        currentLimiterPhaseControls.add(phaseControl);
                        break;
                    default:
                        break;
                }
            });
        }

        OuterLoopStatus status = OuterLoopStatus.STABLE;

        if (currentLimiterPhaseControls.isEmpty() && activePowerControlPhaseControls.isEmpty()) {
            return new OuterLoopResult(this, status);
        }

        var sensitivityContext = new AcSensitivityContext(network,
                                                        controllerBranches,
                                                        context.getLoadFlowContext().getEquationSystem(),
                                                        context.getLoadFlowContext().getJacobianMatrix());

        final int numOfCurrentLimiterPstsThatChangedTap;
        final int numOfActivePowerControlPstsThatChangedTap;
        if (!currentLimiterPhaseControls.isEmpty()) {
            numOfCurrentLimiterPstsThatChangedTap = checkCurrentLimiterPhaseControls(sensitivityContext,
                                                                                         contextData,
                                                                                         currentLimiterPhaseControls);
        } else {
            numOfCurrentLimiterPstsThatChangedTap = 0;
        }

        if (!activePowerControlPhaseControls.isEmpty()) {
            numOfActivePowerControlPstsThatChangedTap = checkActivePowerControlPhaseControls(sensitivityContext,
                                                                                                 contextData,
                                                                                                 activePowerControlPhaseControls);
        } else {
            numOfActivePowerControlPstsThatChangedTap = 0;
        }

        if (numOfCurrentLimiterPstsThatChangedTap + numOfActivePowerControlPstsThatChangedTap != 0) {
            status = OuterLoopStatus.UNSTABLE;
            ReportNode iterationReportNode = Reports.createOuterLoopIterationReporter(reportNode, context.getOuterLoopTotalIterations() + 1);
            if (numOfCurrentLimiterPstsThatChangedTap != 0) {
                Reports.reportCurrentLimiterPstsChangedTaps(iterationReportNode, numOfCurrentLimiterPstsThatChangedTap);
            }
            if (numOfActivePowerControlPstsThatChangedTap != 0) {
                Reports.reportActivePowerControlPstsChangedTaps(iterationReportNode, numOfActivePowerControlPstsThatChangedTap);
            }
        }

        return new OuterLoopResult(this, status);
    }
}
