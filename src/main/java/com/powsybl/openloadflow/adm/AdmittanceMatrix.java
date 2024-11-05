/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.network.LfBus;
import org.apache.commons.math3.complex.Complex;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 */
public class AdmittanceMatrix implements AutoCloseable {

    private final EquationSystem<VariableType, EquationType> equationSystem;

    private final Matrix matrix;

    private LUDecomposition lu;

    private DenseMatrix e; // impedance extractor

    public AdmittanceMatrix(EquationSystem<VariableType, EquationType> equationSystem, Matrix matrix) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.matrix = Objects.requireNonNull(matrix);
    }

    public static AdmittanceMatrix create(AdmittanceEquationSystem ySystem, MatrixFactory matrixFactory) {
        Objects.requireNonNull(matrixFactory);
        try (var j = new JacobianMatrix<>(ySystem.getEquationSystem(), matrixFactory)) {
            return new AdmittanceMatrix(ySystem.getEquationSystem(), j.getMatrix());
        }
    }

    public Matrix getMatrix() {
        return matrix;
    }

    /**
     * Get equivalent impedance between 2 buses.
     */
    public double getZ(LfBus bus1, LfBus bus2) {
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        var yr1Eq = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_YR).orElseThrow();
        var yi1Eq = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_YI).orElseThrow();
        var yr2Eq = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_YR).orElseThrow();
        var yi2Eq = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_YI).orElseThrow();
        if (e == null) {
            e = new DenseMatrix(matrix.getRowCount(), 4);
        }
        e.reset();
        e.set(yr1Eq.getColumn(), 0, 1.0);
        e.set(yi1Eq.getColumn(), 1, 1.0);
        e.set(yr2Eq.getColumn(), 2, 1.0);
        e.set(yi2Eq.getColumn(), 3, 1.0);
        if (lu == null) {
            lu = matrix.decomposeLU();
        }
        lu.solveTransposed(e);
        double zr12 = e.get(yr1Eq.getColumn(), 2);
        double zi12 = e.get(yi1Eq.getColumn(), 2);
        double zr21 = e.get(yr2Eq.getColumn(), 0);
        double zi21 = e.get(yi2Eq.getColumn(), 0);
        double zr11 = e.get(yr1Eq.getColumn(), 0);
        double zi11 = e.get(yi1Eq.getColumn(), 0);
        double zr22 = e.get(yr2Eq.getColumn(), 2);
        double zi22 = e.get(yi2Eq.getColumn(), 2);
        Complex z12 = new Complex(zr12, zi12);
        Complex z21 = new Complex(zr21, zi21);
        Complex z11 = new Complex(zr11, zi11);
        Complex z22 = new Complex(zr22, zi22);
        Complex z = (z11.multiply(z22).subtract(z12.multiply(z21))).divide(z12);
        return z.abs();
    }

    @Override
    public void close() {
        lu.close();
    }
}
