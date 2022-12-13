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

/**
 * State vector rescaler.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public interface StateVectorRescaler {

    /**
     * Rescale state vector variation before equation mismatches calculation.
     */
    void rescale(double[] dx, EquationSystem<AcVariableType, AcEquationType> equationSystem);

    /**
     * Rescale state vector after equation mismatches and norm have been calculated.
     */
    NewtonRaphsonStoppingCriteria.TestResult rescaleAfter(StateVector stateVector,
                                                          EquationVector<AcVariableType, AcEquationType> equationVector,
                                                          TargetVector<AcVariableType, AcEquationType> targetVector,
                                                          NewtonRaphsonStoppingCriteria stoppingCriteria,
                                                          NewtonRaphsonStoppingCriteria.TestResult testResult);
}
