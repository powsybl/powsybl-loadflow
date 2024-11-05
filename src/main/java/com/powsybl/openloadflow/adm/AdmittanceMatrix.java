/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.network.LfBus;

import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class AdmittanceMatrix {

    private final EquationSystem<VariableType, EquationType> equationSystem;

    private final Matrix matrix;

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

    public double getZ(LfBus bus1, LfBus bus2) {
        Objects.requireNonNull(bus1);
        Objects.requireNonNull(bus2);
        //matrix.print(System.out);
        var yrEq1 = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_YR).orElseThrow();
        var yiEq1 = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_YI).orElseThrow();
        var yrEq2 = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_YR).orElseThrow();
        var yiEq2 = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_YI).orElseThrow();
        DenseMatrix e = new DenseMatrix(matrix.getRowCount(), 2);
        e.set(yrEq1.getColumn(), 0, 1.0);
//        e.set(yiEq1.getColumn(), 1, 1.0);
    //    e.print(System.out);
        try (var lu = matrix.decomposeLU()) {
            lu.solveTransposed(e);
        }
//        System.out.println("ddd");
//        e.print(System.out);
        double zr = e.get(yrEq2.getColumn(), 0);
        double zi = e.get(yiEq2.getColumn(), 0);
        System.out.println("zr=" + zr + ", zi=" + zi);
        return Math.hypot(zr, zi);
    }

    public void full() {
        matrix.print(System.out);
        DenseMatrix e = new DenseMatrix(matrix.getRowCount(), matrix.getColumnCount());
        for (int i = 0; i < matrix.getRowCount(); i = i + 2) {
            e.set(i, i, 1.0);
        }
        e.print(System.out);
        try (var lu = matrix.decomposeLU()) {
            lu.solveTransposed(e);
        }
        System.out.println("ddd");
        e.print(System.out);
    }
}
