/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.PiModel;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
abstract class AbstractBranchAcFlowEquationTerm extends AbstractElementEquationTerm<LfBranch, AcVariableType, AcEquationType> {

    protected static final int DV1 = 0;
    protected static final int DV2 = 1;
    protected static final int DPH1 = 2;
    protected static final int DPH2 = 3;
    protected static final int DA1 = 4;
    protected static final int DR1 = 5;

    protected final double b1;
    protected final double b2;
    protected final double g1;
    protected final double g2;
    protected final double y;
    protected final double ksi;

    protected AbstractBranchAcFlowEquationTerm(LfBranch branch) {
        super(branch);
        PiModel piModel = branch.getPiModel();
        if (piModel.getR() == 0 && piModel.getX() == 0) {
            throw new IllegalArgumentException("Non impedant branch not supported: " + branch.getId());
        }
        b1 = piModel.getB1();
        b2 = piModel.getB2();
        g1 = piModel.getG1();
        g2 = piModel.getG2();
        y = piModel.getY();
        ksi = piModel.getKsi();
    }
}
