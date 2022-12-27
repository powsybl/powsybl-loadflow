/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import net.jafama.FastMath;

import static com.powsybl.openloadflow.network.PiModel.R2;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@SuppressWarnings("squid:S00107")
public class ClosedBranchSide2ActiveFlowEquationTerm extends AbstractClosedBranchAcFlowEquationTerm {

    public ClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet,
                                                   boolean deriveA1, boolean deriveR1) {
        super(branch, bus1, bus2, variableSet, deriveA1, deriveR1);
    }

    protected double calculateSensi(double dph1, double dph2, double dv1, double dv2, double da1, double dr1) {
        double v1 = v1();
        double r1 = r1();
        double v2 = v2();
        double theta = theta2(ksi, ph1(), a1(), ph2());
        double cosTheta = FastMath.cos(theta);
        double sinTheta = FastMath.sin(theta);
        return dp2dph1(y, v1, r1, v2, cosTheta) * dph1
                + dp2dph2(y, v1, r1, v2, cosTheta) * dph2
                + dp2dv1(y, r1, v2, sinTheta) * dv1
                + dp2dv2(y, FastMath.sin(ksi), g2, v1, r1, v2, sinTheta) * dv2;
    }

    public static double p2(double y, double sinKsi, double g2, double v1, double r1, double v2, double sinTheta) {
        return R2 * v2 * (g2 * R2 * v2 - y * r1 * v1 * sinTheta + y * R2 * v2 * sinKsi);
    }

    private static double dp2dv1(double y, double r1, double v2, double sinTheta) {
        return -y * r1 * R2 * v2 * sinTheta;
    }

    private static double dp2dv2(double y, double sinKsi, double g2, double v1, double r1, double v2, double sinTheta) {
        return R2 * (2 * g2 * R2 * v2 - y * r1 * v1 * sinTheta + 2 * y * R2 * v2 * sinKsi);
    }

    private static double dp2dph1(double y, double v1, double r1, double v2, double cosTheta) {
        return -y * r1 * R2 * v1 * v2 * cosTheta;
    }

    private static double dp2dph2(double y, double v1, double r1, double v2, double cosTheta) {
        return -dp2dph1(y, v1, r1, v2, cosTheta);
    }

    private static double dp2da1(double y, double v1, double r1, double v2, double cosTheta) {
        return dp2dph1(y, v1, r1, v2, cosTheta);
    }

    private static double dp2dr1(double y, double v1, double v2, double sinTheta) {
        return -y * R2 * v1 * v2 * sinTheta;
    }

    @Override
    public double eval() {
        return p2(y, FastMath.sin(ksi), g2, v1(), r1(), v2(), FastMath.sin(theta2(ksi, ph1(), a1(), ph2())));
    }

    @Override
    public double der(int index) {
        double theta = theta2(ksi, ph1(), a1(), ph2());
        switch (index) {
            case DV1:
                return dp2dv1(y, r1(), v2(), FastMath.sin(theta));
            case DV2:
                return dp2dv2(y, FastMath.sin(ksi), g2, v1(), r1(), v2(), FastMath.sin(theta));
            case DPH1:
                return dp2dph1(y, v1(), r1(), v2(), FastMath.cos(theta));
            case DPH2:
                return dp2dph2(y, v1(), r1(), v2(), FastMath.cos(theta));
            case DA1:
                return dp2da1(y, v1(), r1(), v2(), FastMath.cos(theta));
            case DR1:
                return dp2dr1(y, v1(), v2(), FastMath.sin(theta));
            default:
                return super.der(index);
        }
    }

    @Override
    protected String getName() {
        return "ac_p_closed_2";
    }
}
