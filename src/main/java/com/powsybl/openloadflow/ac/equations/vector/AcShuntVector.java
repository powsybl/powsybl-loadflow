/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcShuntVector {

    final int[] busNum;

    final boolean[] disabled;

    final double[] g;
    final double[] b;

    final boolean[] deriveB;

    public final int[] bRow;

    final double[] p;
    final double[] q;

    final double[] dpdv;
    final double[] dqdv;
    final double[] dqdb;

    public AcShuntVector(List<LfShunt> shunts) {
        int size = shunts.size();
        busNum = new int[size];
        disabled = new boolean[size];
        g = new double[size];
        b = new double[size];
        deriveB = new boolean[size];

        bRow = new int[size];

        p = new double[size];
        q = new double[size];
        dpdv = new double[size];
        dqdv = new double[size];
        dqdb = new double[size];

        for (int i = 0; i < size; i++) {
            LfShunt shunt = shunts.get(i);
            LfBus bus = shunt.getBus();
            busNum[i] = bus != null ? bus.getNum() : -1;
            disabled[i] = shunt.isDisabled();
            g[i] = shunt.getG();
            b[i] = shunt.getB();
            deriveB[i] = shunt.hasVoltageControlCapability();
        }
    }

    public int getSize() {
        return busNum.length;
    }
}
