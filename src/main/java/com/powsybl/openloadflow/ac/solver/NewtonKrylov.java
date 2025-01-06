/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.math.solver.Kinsol;
import com.powsybl.math.solver.KinsolParameters;
import com.powsybl.math.solver.KinsolResult;
import com.powsybl.math.solver.KinsolStatus;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class NewtonKrylov extends AbstractAcSolver {

    private final NewtonKrylovParameters parameters;

    public NewtonKrylov(LfNetwork network, NewtonKrylovParameters parameters,
                        EquationSystem<AcVariableType, AcEquationType> equationSystem,
                        JacobianMatrix<AcVariableType, AcEquationType> j, TargetVector<AcVariableType, AcEquationType> targetVector,
                        EquationVector<AcVariableType, AcEquationType> equationVector) {
        super(network, equationSystem, j, targetVector, equationVector, false);
        this.parameters = Objects.requireNonNull(parameters);
    }

    @Override
    public String getName() {
        return "Newton Krylov";
    }

    private AcSolverStatus getStatus(KinsolStatus status) {
        return switch (status) {
            case KIN_SUCCESS, KIN_INITIAL_GUESS_OK -> AcSolverStatus.CONVERGED;
            case KIN_MAXITER_REACHED -> AcSolverStatus.MAX_ITERATION_REACHED;
            default -> AcSolverStatus.SOLVER_FAILED;
        };
    }

    @Override
    public AcSolverResult run(VoltageInitializer voltageInitializer, ReportNode reportNode, boolean canCheckUnrealistic) {
        // initialize state vector
        AcSolverUtil.initStateVector(network, equationSystem, voltageInitializer);

        KinsolParameters kinsolParameters = new KinsolParameters()
                .setMaxIters(parameters.getMaxIterations())
                .setLineSearch(parameters.isLineSearch());
        Kinsol kinsol = new Kinsol((SparseMatrix) j.getMatrix(), (x, f) -> {
            equationSystem.getStateVector().set(x);
            equationVector.minus(targetVector);
            System.arraycopy(equationVector.getArray(), 0, f, 0, equationVector.getArray().length);
        }, (x, j) -> {
            // force jacobian values update because on C side we directly get access to internal data structure which
            // does not update with last state vector
            NewtonKrylov.this.j.forceUpdate();
        });
        KinsolResult result = kinsol.solveTransposed(equationSystem.getStateVector().get(), kinsolParameters);

        if (result.getStatus() == KinsolStatus.KIN_SUCCESS) {
            AcSolverUtil.updateNetwork(network, equationSystem);
        }
        return new AcSolverResult(getStatus(result.getStatus()), (int) result.getIterations(), 0);
    }
}
