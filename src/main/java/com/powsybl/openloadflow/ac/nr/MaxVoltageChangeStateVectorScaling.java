/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.nr;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.TargetVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limit voltage magnitude change and voltage angle change between NR iterations
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class MaxVoltageChangeStateVectorScaling implements StateVectorScaling {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxVoltageChangeStateVectorScaling.class);

    private static final double DEFAULT_MAX_DV = 0.1;
    private static final double DEFAULT_MAX_DPHI = Math.toRadians(10);

    private final double maxDv;
    private final double maxDphi;

    public MaxVoltageChangeStateVectorScaling() {
        this(DEFAULT_MAX_DV, DEFAULT_MAX_DPHI);
    }

    public MaxVoltageChangeStateVectorScaling(double maxDv, double maxDphi) {
        this.maxDv = maxDv;
        this.maxDphi = maxDphi;
    }

    @Override
    public StateVectorScalingMode getMode() {
        return StateVectorScalingMode.MAX_VOLTAGE_CHANGE;
    }

    @Override
    public void apply(double[] dx, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        int vCutCount = 0;
        int phiCutCount = 0;
        for (var variable : equationSystem.getIndex().getSortedVariablesToFind()) {
            int row = variable.getRow();
            double value = dx[row];
            switch (variable.getType()) {
                case BUS_V:
                    if (Math.abs(value) > maxDv) {
                        dx[row] = Math.copySign(maxDv, value);
                        vCutCount++;
                    }
                    break;
                case BUS_PHI:
                    if (Math.abs(value) > maxDphi) {
                        dx[row] = Math.copySign(maxDphi, value);
                        phiCutCount++;
                    }
                    break;
                default:
                    break;
            }
        }
        if (vCutCount > 0) {
            LOGGER.debug("{} voltage magnitude changes have been cut", vCutCount);
        }
        if (phiCutCount > 0) {
            LOGGER.debug("{} voltage angle changes have been cut", phiCutCount);
        }
    }

    @Override
    public NewtonRaphsonStoppingCriteria.TestResult applyAfter(StateVector stateVector,
                                                               EquationVector<AcVariableType, AcEquationType> equationVector,
                                                               TargetVector<AcVariableType, AcEquationType> targetVector,
                                                               NewtonRaphsonStoppingCriteria stoppingCriteria,
                                                               NewtonRaphsonStoppingCriteria.TestResult testResult) {
        // nothing to do
        return testResult;
    }
}
