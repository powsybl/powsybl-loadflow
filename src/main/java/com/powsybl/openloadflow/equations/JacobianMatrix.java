/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.math.matrix.MatrixFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.powsybl.openloadflow.util.Markers.PERFORMANCE_MARKER;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class JacobianMatrix<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        implements EquationSystemIndexListener<V, E>, StateVectorListener, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JacobianMatrix.class);

    private final EquationSystem<V, E> equationSystem;

    private final MatrixFactory matrixFactory;

    private Matrix matrix;

    private LUDecomposition lu;

    private final VectorEngine<V> vectorEngine;

    protected enum Status {
        VALID,
        VALUES_INVALID, // same structure but values have to be updated
        VALUES_AND_ZEROS_INVALID, // same structure but values have to be updated and non zero values might have changed
        STRUCTURE_INVALID, // structure has changed
    }

    private Status status = Status.STRUCTURE_INVALID;

    public JacobianMatrix(EquationSystem<V, E> equationSystem, MatrixFactory matrixFactory) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        equationSystem.getIndex().addListener(this);
        equationSystem.getStateVector().addListener(this);
        vectorEngine = equationSystem.getVectorEngine();
    }

    protected void updateStatus(Status status) {
        if (status.ordinal() > this.status.ordinal()) {
            this.status = status;
        }
    }

    @Override
    public void onEquationChange(Equation<V, E> equation, ChangeType changeType) {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onVariableChange(Variable<V> variable, ChangeType changeType) {
        updateStatus(Status.STRUCTURE_INVALID);
    }

    @Override
    public void onEquationTermChange(EquationTerm<V, E> term) {
        updateStatus(Status.VALUES_AND_ZEROS_INVALID);
    }

    @Override
    public void onStateUpdate() {
        updateStatus(Status.VALUES_INVALID);
    }

    private void initDer() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        int rowCount = equationSystem.getIndex().getSortedEquationsToSolve().size();
        int columnCount = equationSystem.getIndex().getSortedVariablesToFind().size();
        if (rowCount != columnCount) {
            throw new PowsyblException("Expected to have same number of equations (" + rowCount
                    + ") and variables (" + columnCount + ")");
        }

        int estimatedNonZeroValueCount = rowCount * 3;
        matrix = matrixFactory.create(rowCount, columnCount, estimatedNonZeroValueCount);

        if (vectorEngine != null) {
            vectorEngine.der(false, matrix);
        } else {
            for (Equation<V, E> eq : equationSystem.getIndex().getSortedEquationsToSolve()) {
                int column = eq.getColumn();
                eq.der((variable, value, matrixElementIndex) -> {
                    int row = variable.getRow();
                    return matrix.addAndGetIndex(row, column, value);
                });
            }
        }

        LOGGER.debug(PERFORMANCE_MARKER, "Jacobian matrix built in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    private void clearLu() {
        if (lu != null) {
            lu.close();
        }
        lu = null;
    }

    private void initMatrix() {
        initDer();
        clearLu();
    }

    private void updateDer() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        matrix.reset();

        if (vectorEngine != null) {
            vectorEngine.der(true, matrix);
        } else {
            for (Equation<V, E> eq : equationSystem.getIndex().getSortedEquationsToSolve()) {
                eq.der((variable, value, matrixElementIndex) -> {
                    matrix.addAtIndex(matrixElementIndex, value);
                    return matrixElementIndex; // don't change element index
                });
            }
        }

        LOGGER.debug(PERFORMANCE_MARKER, "Jacobian matrix values updated in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    private void updateLu(boolean allowIncrementalUpdate) {
        if (lu != null) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            lu.update(allowIncrementalUpdate);

            LOGGER.debug(PERFORMANCE_MARKER, "LU decomposition updated in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
        }
    }

    private void updateValues(boolean allowIncrementalUpdate) {
        updateDer();
        try {
            updateLu(allowIncrementalUpdate);
        } catch (MatrixException ex) {
            if (allowIncrementalUpdate) {
                // Try another time without incremental
                LOGGER.warn("Exception when updating LU matrix in incremental mode. Retrying without incremental mode");
                updateLu(false);
            } else {
                // Rethrow the exception
                throw ex;
            }
        }
    }

    public void forceUpdate() {
        update();
    }

    private void update() {
        if (status != Status.VALID) {
            switch (status) {
                case STRUCTURE_INVALID:
                    initMatrix();
                    break;

                case VALUES_INVALID:
                    updateValues(true);
                    break;

                case VALUES_AND_ZEROS_INVALID:
                    updateValues(false);
                    break;

                default:
                    break;
            }
            status = Status.VALID;
        }
    }

    public Matrix getMatrix() {
        update();
        return matrix;
    }

    private LUDecomposition getLUDecomposition() {
        Matrix m = getMatrix();
        if (lu == null) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            lu = m.decomposeLU();

            LOGGER.debug(PERFORMANCE_MARKER, "LU decomposition done in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
        }
        return lu;
    }

    public void solve(double[] b) {
        getLUDecomposition().solve(b);
    }

    public void solveTransposed(double[] b) {
        getLUDecomposition().solveTransposed(b);
    }

    public void solve(DenseMatrix b) {
        getLUDecomposition().solve(b);
    }

    public void solveTransposed(DenseMatrix b) {
        getLUDecomposition().solveTransposed(b);
    }

    @Override
    public void close() {
        equationSystem.getIndex().removeListener(this);
        equationSystem.getStateVector().removeListener(this);
        matrix = null;
        clearLu();
    }
}
