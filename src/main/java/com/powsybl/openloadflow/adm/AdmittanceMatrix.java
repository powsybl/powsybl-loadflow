/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.JacobianMatrix;

import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class AdmittanceMatrix {

    private final Matrix matrix;

    public AdmittanceMatrix(Matrix matrix) {
        this.matrix = Objects.requireNonNull(matrix);
    }

    public static AdmittanceMatrix create(AdmittanceEquationSystem ySystem, MatrixFactory matrixFactory) {
        Objects.requireNonNull(matrixFactory);
        try (var j = new JacobianMatrix<>(ySystem.getEquationSystem(), matrixFactory)) {
            return new AdmittanceMatrix(j.getMatrix());
        }
    }

    public Matrix getMatrix() {
        return matrix;
    }

//    public double getY(LfBus bus1, LfBus bus2) {
//        return getYorZ(matrix, bus1, bus2);
//    }

}
