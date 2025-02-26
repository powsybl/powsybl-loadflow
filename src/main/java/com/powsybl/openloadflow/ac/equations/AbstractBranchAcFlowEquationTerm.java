/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.AbstractElementEquationTerm;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.PiModel;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
abstract class AbstractBranchAcFlowEquationTerm extends AbstractElementEquationTerm<LfBranch, AcVariableType, AcEquationType> {

    protected final AcVectorEngine acVectorEnginee;
    protected final int branchNum;

    protected AbstractBranchAcFlowEquationTerm(LfBranch branch, AcVectorEngine acVectorEnginee) {
        super(branch);
        PiModel piModel = branch.getPiModel();
        if (piModel.getR() == 0 && piModel.getX() == 0) {
            throw new IllegalArgumentException("Non impedant branch not supported: " + branch.getId());
        }
        branchNum = branch.getNum();
        this.acVectorEnginee = acVectorEnginee;
        if (!acVectorEnginee.networkDataInitialized[branchNum]) {
            acVectorEnginee.b1[branchNum] = piModel.getB1();
            acVectorEnginee.b2[branchNum] = piModel.getB2();
            acVectorEnginee.g1[branchNum] = piModel.getG1();
            acVectorEnginee.g2[branchNum] = piModel.getG2();
            acVectorEnginee.y[branchNum] = piModel.getY();
            acVectorEnginee.ksi[branchNum] = piModel.getKsi();
            // y12 = g12+j.b12 = 1/(r+j.x)
            acVectorEnginee.g12[branchNum] = piModel.getR() * y() * y();
            acVectorEnginee.b12[branchNum] = -piModel.getX() * y() * y();
            acVectorEnginee.networkDataInitialized[branchNum] = true;
        }
    }

    protected double b1() {
        return acVectorEnginee.b1[branchNum];
    }

    protected double b2() {
        return acVectorEnginee.b2[branchNum];
    }

    protected double g1() {
        return acVectorEnginee.g1[branchNum];
    }

    protected double g2() {
        return acVectorEnginee.g2[branchNum];
    }

    protected double y() {
        return acVectorEnginee.y[branchNum];
    }

    protected double ksi() {
        return acVectorEnginee.ksi[branchNum];
    }

    protected double g12() {
        return acVectorEnginee.g12[branchNum];
    }

    protected double b12() {
        return acVectorEnginee.b12[branchNum];
    }

}
