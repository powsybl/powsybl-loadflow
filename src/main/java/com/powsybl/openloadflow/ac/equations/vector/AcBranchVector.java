/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreationParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationSystemCreator;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.PiModel;
import net.jafama.FastMath;

import java.util.List;

/**
 * Vectorized view of the branches and variables related to branches.
 * Are included all power flows and theirs partial derivatives.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcBranchVector {

    final int[] bus1Num;
    final int[] bus2Num;

    final boolean[] connected1;
    final boolean[] connected2;

    final double[] y;
    final double[] g12;
    final double[] b12;
    final double[] ksi;
    final double[] cosKsi;
    final double[] sinKsi;
    final double[] g1;
    final double[] g2;
    final double[] b1;
    final double[] b2;
    final double[] r1;
    final double[] a1;

    final boolean[] disabled;

    final boolean[] deriveA1;
    final boolean[] deriveR1;

    public final int[] v1Row;
    public final int[] v2Row;
    public final int[] ph1Row;
    public final int[] ph2Row;

    public final int[] a1Row;
    public final int[] r1Row;

    final double[] p1;
    final double[] p2;
    final double[] q1;
    final double[] q2;
    final double[] i1;
    final double[] i2;

    final double[] dp1dv1;
    final double[] dp1dv2;
    final double[] dp1dph1;
    final double[] dp1dph2;
    final double[] dp1da1;
    final double[] dp1dr1;

    final double[] dq1dv1;
    final double[] dq1dv2;
    final double[] dq1dph1;
    final double[] dq1dph2;
    final double[] dq1da1;
    final double[] dq1dr1;

    final double[] dp2dv1;
    final double[] dp2dv2;
    final double[] dp2dph1;
    final double[] dp2dph2;
    final double[] dp2da1;
    final double[] dp2dr1;

    final double[] dq2dv1;
    final double[] dq2dv2;
    final double[] dq2dph1;
    final double[] dq2dph2;
    final double[] dq2da1;
    final double[] dq2dr1;

    public AcBranchVector(List<LfBranch> branches, AcEquationSystemCreationParameters creationParameters) {
        int size = branches.size();
        bus1Num = new int[size];
        bus2Num = new int[size];
        connected1 = new boolean[size];
        connected2 = new boolean[size];
        y = new double[size];
        g12 = new double[size];
        b12 = new double[size];
        ksi = new double[size];
        cosKsi = new double[size];
        sinKsi = new double[size];
        g1 = new double[size];
        g2 = new double[size];
        b1 = new double[size];
        b2 = new double[size];
        r1 = new double[size];
        a1 = new double[size];
        disabled = new boolean[size];

        deriveA1 = new boolean[size];
        deriveR1 = new boolean[size];

        v1Row = new int[size];
        v2Row = new int[size];
        ph1Row = new int[size];
        ph2Row = new int[size];
        a1Row = new int[size];
        r1Row = new int[size];

        p1 = new double[size];
        p2 = new double[size];
        q1 = new double[size];
        q2 = new double[size];
        i1 = new double[size];
        i2 = new double[size];

        dp1dv1 = new double[size];
        dp1dv2 = new double[size];
        dp1dph1 = new double[size];
        dp1dph2 = new double[size];
        dp1da1 = new double[size];
        dp1dr1 = new double[size];

        dq1dv1 = new double[size];
        dq1dv2 = new double[size];
        dq1dph1 = new double[size];
        dq1dph2 = new double[size];
        dq1da1 = new double[size];
        dq1dr1 = new double[size];

        dp2dv1 = new double[size];
        dp2dv2 = new double[size];
        dp2dph1 = new double[size];
        dp2dph2 = new double[size];
        dp2da1 = new double[size];
        dp2dr1 = new double[size];

        dq2dv1 = new double[size];
        dq2dv2 = new double[size];
        dq2dph1 = new double[size];
        dq2dph2 = new double[size];
        dq2da1 = new double[size];
        dq2dr1 = new double[size];

        for (int i = 0; i < branches.size(); i++) {
            LfBranch branch = branches.get(i);
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            bus1Num[i] = bus1 != null ? bus1.getNum() : -1;
            bus2Num[i] = bus2 != null ? bus2.getNum() : -1;
            connected1[i] = branch.isConnectedSide1();
            connected2[i] = branch.isConnectedSide2();
            PiModel piModel = branch.getPiModel();
            if (piModel.getZ() != 0) {
                y[i] = piModel.getY();
                // y12 = g12+j.b12 = 1/(r+j.x)
                g12[i] = piModel.getR() * y[i] * y[i];
                b12[i] = -piModel.getX() * y[i] * y[i];
                ksi[i] = piModel.getKsi();
                cosKsi[i] = FastMath.cos(ksi[i]);
                sinKsi[i] = FastMath.sin(ksi[i]);
            }
            b1[i] = piModel.getB1();
            b2[i] = piModel.getB2();
            g1[i] = piModel.getG1();
            g2[i] = piModel.getG2();
            r1[i] = piModel.getR1();
            a1[i] = piModel.getA1();
            disabled[i] = branch.isDisabled();
            deriveA1[i] = AcEquationSystemCreator.isDeriveA1(branch, creationParameters);
            deriveR1[i] = AcEquationSystemCreator.isDeriveR1(branch);
        }
    }

    public int getSize() {
        return disabled.length;
    }
}
