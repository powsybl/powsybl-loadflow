/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfHvdc;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class HvdcAcEmulationSide1ActiveFlowEquationTerm extends AbstractHvdcAcEmulationFlowEquationTerm {

    public HvdcAcEmulationSide1ActiveFlowEquationTerm(LfHvdc hvdc, LfBus bus1, LfBus bus2, VariableSet<AcVariableType> variableSet) {
        super(hvdc, bus1, bus2, variableSet);
    }

    private static double p1(double p0, double k, double lossFactor1, double lossFactor2, double ph1, double ph2) {
        return (isController(ph1, ph2) ? 1 : getLossMultiplier(lossFactor1, lossFactor2)) * (p0 + k * (ph1 - ph2));
    }

    private static boolean isController(double ph1, double ph2) {
        return (ph1 - ph2) >= 0;
    }

    private static double dp1dph1(double k, double lossFactor1, double lossFactor2, double ph1, double ph2) {
        return (isController(ph1, ph2) ? 1 : getLossMultiplier(lossFactor1, lossFactor2)) * k;
    }

    private static double dp1dph2(double k, double lossFactor1, double lossFactor2, double ph1, double ph2) {
        return -dp1dph1(k, lossFactor1, lossFactor2, ph1, ph2);
    }

    @Override
    public double eval() {
        return p1(p0, k, lossFactor1, lossFactor2, ph1(), ph2());
    }

    @Override
    public double der(int index) {
        switch (index) {
            case DPH1:
                return dp1dph1(k, lossFactor1, lossFactor2, ph1(), ph2());
            case DPH2:
                return dp1dph2(k, lossFactor1, lossFactor2, ph1(), ph2());
            default:
                return super.der(index);
        }
    }

    @Override
    protected String getName() {
        return "ac_emulation_p_1";
    }
}
