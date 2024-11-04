/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.openloadflow.network.LfBus;

import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public class AdmittanceMatrix {

    private final Matrix matrix;

    private Matrix matrixZ;

    public AdmittanceMatrix(Matrix matrix) {
        this.matrix = Objects.requireNonNull(matrix);
    }

    public Matrix getMatrix() {
        return matrix;
    }

//    public double getY(LfBus bus1, LfBus bus2) {
//        return getYorZ(matrix, bus1, bus2);
//    }
//
//    public double getZ(LfBus bus1, LfBus bus2) {
//        return getYorZ(getMatrixZ(), bus1, bus2);
//    }
//
//    private static double getYorZ(Matrix matrix, LfBus bus1, LfBus bus2) {
//        Objects.requireNonNull(bus1);
//        Objects.requireNonNull(bus2);
//        if (matrix instanceof DenseMatrix denseMatrix) {
//            return denseMatrix.get(bus1.getNum(), bus2.getNum());
//        } else if (matrix instanceof SparseMatrix sparseMatrix) {
//            // TODO
//        } else {
//            throw new UnsupportedOperationException("Unsupported matrix implementation: " + matrix.getClass().getName());
//        }
//    }
//
//    private Matrix getMatrixZ() {
//        if (matrixZ == null) {
//            // Z = 1 / Y
//
//        }
//        return matrixZ;
//    }
}
