/**
 * Copyright (c) 2023, Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * Copyright (c) 2023, Geoffroy Jamgotchian <geoffroy.jamgotchian at gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

import com.powsybl.math.matrix.DenseMatrix;

/**
 * TODO: move theses features to core.
 *
 * @author Jean-Baptiste Heyberger {@literal <jbheyberger at gmail.com>}
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at gmail.com>}
 */
public final class MatrixUtil {

    private MatrixUtil() {
    }

    public static void resetRow(DenseMatrix m, int row) {
        for (int j = 0; j < m.getColumnCount(); j++) {
            m.set(row, j, 0);
        }
    }

    public static void resetColumn(DenseMatrix m, int column) {
        for (int i = 0; i < m.getRowCount(); i++) {
            m.set(i, column, 0);
        }
    }

    public static void clean(DenseMatrix m, double epsilonValue) {
        for (int i = 0; i < m.getRowCount(); i++) {
            for (int j = 0; j < m.getColumnCount(); j++) {
                if (Math.abs(m.get(i, j)) < epsilonValue) {
                    m.set(i, j, 0.);
                }
            }
        }
    }
}
