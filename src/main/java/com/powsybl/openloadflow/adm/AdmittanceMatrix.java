/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
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
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AdmittanceMatrix implements AutoCloseable {

    private final EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem;

    private final Matrix matrix;

    private LUDecomposition lu;

    private DenseMatrix e; // impedance extractor

    public AdmittanceMatrix(EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem, Matrix matrix) {
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
    public Complex getZ(LfBus bus1, LfBus bus2) {
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        var i1xEq = equationSystem.getEquation(bus1.getNum(), AdmittanceEquationType.BUS_ADM_IX).orElseThrow();
        var i1yEq = equationSystem.getEquation(bus1.getNum(), AdmittanceEquationType.BUS_ADM_IY).orElseThrow();
        var i2xEq = equationSystem.getEquation(bus2.getNum(), AdmittanceEquationType.BUS_ADM_IX).orElseThrow();
        var i2yEq = equationSystem.getEquation(bus2.getNum(), AdmittanceEquationType.BUS_ADM_IY).orElseThrow();
        if (e == null) {
            e = new DenseMatrix(matrix.getRowCount(), 4);
        }
        e.reset();
        e.set(i1xEq.getColumn(), 0, 1.0);
        e.set(i1yEq.getColumn(), 1, 1.0);
        e.set(i2xEq.getColumn(), 2, 1.0);
        e.set(i2yEq.getColumn(), 3, 1.0);
        if (lu == null) {
            lu = matrix.decomposeLU();
        }
        lu.solveTransposed(e);
        double z12x = e.get(i1xEq.getColumn(), 2);
        double z12y = e.get(i1yEq.getColumn(), 2);
        double z21x = e.get(i2xEq.getColumn(), 0);
        double z21y = e.get(i2yEq.getColumn(), 0);
        double z11x = e.get(i1xEq.getColumn(), 0);
        double z11y = e.get(i1yEq.getColumn(), 0);
        double z22x = e.get(i2xEq.getColumn(), 2);
        double z22y = e.get(i2yEq.getColumn(), 2);
        Complex z12 = new Complex(z12x, z12y);
        Complex z21 = new Complex(z21x, z21y);
        Complex z11 = new Complex(z11x, z11y);
        Complex z22 = new Complex(z22x, z22y);
        // z = (z11 * z22 - z12 * z21) / z12
        return (z11.multiply(z22).subtract(z12.multiply(z21))).divide(z12);
    }

    @Override
    public void close() {
        if (lu != null) {
            lu.close();
        }
    }
}
