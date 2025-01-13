/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.google.common.base.Stopwatch;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import net.jafama.DoubleWrapper;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Vectorized view of the network and variables of the equation system.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcNetworkVector extends AbstractLfNetworkListener
        implements EquationSystemIndexListener<AcVariableType, AcEquationType>, StateVectorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcNetworkVector.class);

    private final LfNetwork network;
    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;
    private final AcBusVector busVector;
    private final AcBranchVector branchVector;
    private final AcShuntVector shuntVector;
    private boolean variablesInvalid = true;

    public AcNetworkVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                           AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        busVector = new AcBusVector(network.getBuses());
        branchVector = new AcBranchVector(network.getBranches(), creationParameters);
        shuntVector = new AcShuntVector(network.getShunts());
    }

    public AcBusVector getBusVector() {
        return busVector;
    }

    public AcBranchVector getBranchVector() {
        return branchVector;
    }

    public AcShuntVector getShuntVector() {
        return shuntVector;
    }

    public void startListening() {
        // listen for branch disabling status update
        network.addListener(this);

        // update vectorized variables and then listen for variables change in equation system
        updateVariables();
        equationSystem.getIndex().addListener(this);

        // listen for state vector change to update vectorized network
        equationSystem.getStateVector().addListener(this);
    }

    /**
     * Update vectorized view of the variables from the equation system.
     */
    public void updateVariables() {
        if (!variablesInvalid) {
            return;
        }

        Stopwatch stopwatch = Stopwatch.createStarted();

        Arrays.fill(busVector.vRow, -1);
        Arrays.fill(busVector.phRow, -1);
        Arrays.fill(branchVector.a1Row, -1);
        Arrays.fill(branchVector.r1Row, -1);
        Arrays.fill(branchVector.v1Row, -1);
        Arrays.fill(branchVector.ph1Row, -1);
        Arrays.fill(branchVector.v2Row, -1);
        Arrays.fill(branchVector.ph2Row, -1);
        Arrays.fill(shuntVector.bRow, -1);

        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            int num = v.getElementNum();
            int row = v.getRow();
            switch (v.getType()) {
                case BUS_V:
                    busVector.vRow[num] = row;
                    break;

                case BUS_PHI:
                    busVector.phRow[num] = row;
                    break;

                case BRANCH_ALPHA1:
                    branchVector.a1Row[num] = branchVector.deriveA1[num] ? row : -1;
                    break;

                case BRANCH_RHO1:
                    branchVector.r1Row[num] = branchVector.deriveR1[num] ? row : -1;
                    break;

                case SHUNT_B:
                    shuntVector.bRow[num] = shuntVector.deriveB[num] ? row : -1;
                    break;

                default:
                    break;
            }
        }

        copyVariablesToBranches();

        stopwatch.stop();
        LOGGER.debug("AC variable vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));

        variablesInvalid = false;
    }

    public void copyVariablesToBranches() {
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (branchVector.bus1Num[branchNum] != -1) {
                branchVector.v1Row[branchNum] = busVector.vRow[branchVector.bus1Num[branchNum]];
                branchVector.ph1Row[branchNum] = busVector.phRow[branchVector.bus1Num[branchNum]];
            }
            if (branchVector.bus2Num[branchNum] != -1) {
                branchVector.v2Row[branchNum] = busVector.vRow[branchVector.bus2Num[branchNum]];
                branchVector.ph2Row[branchNum] = busVector.phRow[branchVector.bus2Num[branchNum]];
            }
        }
    }

    private boolean isConnectedSide1(int branchNum) {
        return branchVector.bus1Num[branchNum] != -1 && branchVector.connected1[branchNum];
    }

    private boolean isConnectedSide2(int branchNum) {
        return branchVector.bus2Num[branchNum] != -1 && branchVector.connected2[branchNum];
    }

    /**
     * Update all power flows and their derivatives.
     */
    public void updatePowerFlows() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] state = equationSystem.getStateVector().get();
        var w = new DoubleWrapper();
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (!branchVector.disabled[branchNum]) {
                if (isConnectedSide1(branchNum) && isConnectedSide2(branchNum)) {
                    double ph1 = state[branchVector.ph1Row[branchNum]];
                    double ph2 = state[branchVector.ph2Row[branchNum]];
                    double a1 = branchVector.a1Row[branchNum] != -1 ? state[branchVector.a1Row[branchNum]]
                                                                    : branchVector.a1[branchNum];

                    double theta1 = AbstractClosedBranchVectorAcFlowEquationTerm.theta1(
                            branchVector.ksi[branchNum],
                            ph1,
                            a1,
                            ph2);
                    double theta2 = AbstractClosedBranchVectorAcFlowEquationTerm.theta2(
                            branchVector.ksi[branchNum],
                            ph1,
                            a1,
                            ph2);
                    double sinTheta1 = FastMath.sinAndCos(theta1, w);
                    double cosTheta1 = w.value;
                    double sinTheta2 = FastMath.sinAndCos(theta2, w);
                    double cosTheta2 = w.value;

                    double v1 = state[branchVector.v1Row[branchNum]];
                    double v2 = state[branchVector.v2Row[branchNum]];
                    double r1 = branchVector.r1Row[branchNum] != -1 ? state[branchVector.r1Row[branchNum]]
                                                                    : branchVector.r1[branchNum];

                    // p1

                    branchVector.p1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.p1(
                            branchVector.y[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    branchVector.dp1dv1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv1(
                            branchVector.y[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    branchVector.dp1dv2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            sinTheta1);

                    branchVector.dp1dph1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    branchVector.dp1dph2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    branchVector.dp1da1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1da1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    branchVector.dp1dr1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dr1(
                            branchVector.y[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    // q1

                    branchVector.q1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.q1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.b1[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    branchVector.dq1dv1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.b1[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    branchVector.dq1dv2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            cosTheta1);

                    branchVector.dq1dph1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    branchVector.dq1dph2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    branchVector.dq1da1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1da1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta1);

                    branchVector.dq1dr1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dr1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.b1[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta1);

                    // i1

                    branchVector.i1[branchNum] = FastMath.hypot(branchVector.p1[branchNum], branchVector.q1[branchNum]) / v1;

                    // p2

                    branchVector.p2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.p2(
                            branchVector.y[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g2[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dp2dv1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv1(
                            branchVector.y[branchNum],
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dp2dv2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv2(
                            branchVector.y[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g2[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dp2dph1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dp2dph2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dp2da1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2da1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dp2dr1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dr1(
                            branchVector.y[branchNum],
                            v1,
                            v2,
                            sinTheta2);

                    // q2

                    branchVector.q2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.q2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dq2dv1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv1(
                            branchVector.y[branchNum],
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dq2dv2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1,
                            v2,
                            cosTheta2);

                    branchVector.dq2dph1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dq2dph2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph2(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dq2da1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2da1(
                            branchVector.y[branchNum],
                            v1,
                            r1,
                            v2,
                            sinTheta2);

                    branchVector.dq2dr1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dr1(
                            branchVector.y[branchNum],
                            v1,
                            v2,
                            cosTheta2);

                    // i2

                    branchVector.i2[branchNum] = FastMath.hypot(branchVector.p2[branchNum], branchVector.q2[branchNum]) / v2;
                } else if (isConnectedSide1(branchNum)) {
                    double v1 = state[branchVector.v1Row[branchNum]];
                    double r1 = branchVector.r1Row[branchNum] != -1 ? state[branchVector.r1Row[branchNum]]
                                                                    : branchVector.r1[branchNum];

                    branchVector.p1[branchNum] = OpenBranchSide2ActiveFlowEquationTerm.p1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.g2[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1);

                    branchVector.dp1dv1[branchNum] = OpenBranchSide2ActiveFlowEquationTerm.dp1dv1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.g2[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1);

                    branchVector.q1[branchNum] = OpenBranchSide2ReactiveFlowEquationTerm.q1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.g2[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1);

                    branchVector.dq1dv1[branchNum] = OpenBranchSide2ReactiveFlowEquationTerm.dq1dv1(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.g2[branchNum],
                            branchVector.b2[branchNum],
                            v1,
                            r1);

                    branchVector.i1[branchNum] = FastMath.hypot(branchVector.p1[branchNum], branchVector.q1[branchNum]) / v1;
                } else if (isConnectedSide2(branchNum)) {
                    double v2 = state[branchVector.v2Row[branchNum]];

                    branchVector.p2[branchNum] = OpenBranchSide1ActiveFlowEquationTerm.p2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.g2[branchNum],
                            v2);

                    branchVector.dp2dv2[branchNum] = OpenBranchSide1ActiveFlowEquationTerm.dp2dv2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.g2[branchNum],
                            v2);

                    branchVector.q2[branchNum] = OpenBranchSide1ReactiveFlowEquationTerm.q2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.b2[branchNum],
                            v2);

                    branchVector.dq2dv2[branchNum] = OpenBranchSide1ReactiveFlowEquationTerm.dq2dv2(
                            branchVector.y[branchNum],
                            branchVector.cosKsi[branchNum],
                            branchVector.sinKsi[branchNum],
                            branchVector.g1[branchNum],
                            branchVector.b1[branchNum],
                            branchVector.b2[branchNum],
                            v2);

                    branchVector.i2[branchNum] = FastMath.hypot(branchVector.p2[branchNum], branchVector.q2[branchNum]) / v2;
                }
            }
        }

        for (int shuntNum = 0; shuntNum < shuntVector.getSize(); shuntNum++) {
            if (!shuntVector.disabled[shuntNum]) {
                if (shuntVector.busNum[shuntNum] != -1) {
                    double v = state[busVector.vRow[shuntVector.busNum[shuntNum]]];
                    double b = shuntVector.bRow[shuntNum] != -1 ? state[shuntVector.bRow[shuntNum]] : shuntVector.b[shuntNum];
                    shuntVector.p[shuntNum] = ShuntCompensatorActiveFlowEquationTerm.p(v, shuntVector.g[shuntNum]);
                    shuntVector.dpdv[shuntNum] = ShuntCompensatorActiveFlowEquationTerm.dpdv(v, shuntVector.g[shuntNum]);
                    shuntVector.q[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.q(v, b);
                    shuntVector.dqdv[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.dqdv(v, b);
                    shuntVector.dqdb[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.dqdb(v);
                }
            }
        }

        stopwatch.stop();
        LOGGER.debug("AC network vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        if (element.getType() == ElementType.BRANCH) {
            branchVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.SHUNT_COMPENSATOR) {
            shuntVector.disabled[element.getNum()] = disabled;
        }
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        if (side == TwoSides.ONE) {
            branchVector.connected1[branch.getNum()] = connected;
        } else {
            branchVector.connected2[branch.getNum()] = connected;
        }
    }

    @Override
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
        PiModel piModel = branch.getPiModel();
        branchVector.a1[branch.getNum()] = piModel.getA1();
        branchVector.r1[branch.getNum()] = piModel.getR1();
    }

    @Override
    public void onShuntSusceptanceChange(LfShunt shunt, double b) {
        shuntVector.b[shunt.getNum()] = b;
    }

    @Override
    public void onVariableChange(Variable<AcVariableType> variable, ChangeType changeType) {
        variablesInvalid = true;
    }

    @Override
    public void onEquationChange(Equation<AcVariableType, AcEquationType> equation, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermChange(EquationTerm<AcVariableType, AcEquationType> term) {
        // nothing to do
    }

    @Override
    public void onStateUpdate() {
        updateVariables();
        updatePowerFlows();
    }
}
