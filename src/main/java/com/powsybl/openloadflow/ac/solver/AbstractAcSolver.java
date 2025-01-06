/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractAcSolver implements AcSolver {

    protected final LfNetwork network;

    protected final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    protected final JacobianMatrix<AcVariableType, AcEquationType> j;

    protected final TargetVector<AcVariableType, AcEquationType> targetVector;

    protected final EquationVector<AcVariableType, AcEquationType> equationVector;

    protected boolean detailedReport;

    protected AbstractAcSolver(LfNetwork network,
                               EquationSystem<AcVariableType, AcEquationType> equationSystem,
                               JacobianMatrix<AcVariableType, AcEquationType> j,
                               TargetVector<AcVariableType, AcEquationType> targetVector,
                               EquationVector<AcVariableType, AcEquationType> equationVector,
                               boolean detailedReport) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.j = Objects.requireNonNull(j);
        this.targetVector = Objects.requireNonNull(targetVector);
        this.equationVector = Objects.requireNonNull(equationVector);
        this.detailedReport = detailedReport;
    }

}
