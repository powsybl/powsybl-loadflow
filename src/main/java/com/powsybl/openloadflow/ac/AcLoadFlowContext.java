/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.asym.AsymmetricalAcEquationSystemCreator;
import com.powsybl.openloadflow.ac.equations.vector.AcVectorizedEquationSystemCreator;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.lf.AbstractLoadFlowContext;
import com.powsybl.openloadflow.network.LfNetwork;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcLoadFlowContext extends AbstractLoadFlowContext<AcVariableType, AcEquationType, AcLoadFlowParameters> {

    private AcTargetVector targetVector;

    private EquationVector<AcVariableType, AcEquationType> equationVector;

    private AcLoadFlowResult result;

    private boolean networkUpdated = true;

    public AcLoadFlowContext(LfNetwork network, AcLoadFlowParameters parameters) {
        super(network, parameters);
    }

    @Override
    public JacobianMatrix<AcVariableType, AcEquationType> getJacobianMatrix() {
        if (jacobianMatrix == null) {
            jacobianMatrix = new AcJacobianMatrix(getEquationSystem(), parameters.getMatrixFactory(), network);
        }
        return jacobianMatrix;
    }

    @Override
    public EquationSystem<AcVariableType, AcEquationType> getEquationSystem() {
        if (equationSystem == null) {
            var creator = parameters.isAsymmetrical() ? new AsymmetricalAcEquationSystemCreator(network, parameters.getEquationSystemCreationParameters())
                                                      : new AcVectorizedEquationSystemCreator(network, parameters.getEquationSystemCreationParameters());
            equationSystem = creator.create();
        }
        return equationSystem;
    }

    @Override
    public TargetVector<AcVariableType, AcEquationType> getTargetVector() {
        if (targetVector == null) {
            targetVector = new AcTargetVector(network, getEquationSystem());
        }
        return targetVector;
    }

    public EquationVector<AcVariableType, AcEquationType> getEquationVector() {
        if (equationVector == null) {
            equationVector = new EquationVector<>(getEquationSystem());
        }
        return equationVector;
    }

    public AcLoadFlowResult getResult() {
        return result;
    }

    public void setResult(AcLoadFlowResult result) {
        this.result = result;
    }

    public boolean isNetworkUpdated() {
        return networkUpdated;
    }

    public void setNetworkUpdated(boolean networkUpdated) {
        this.networkUpdated = networkUpdated;
    }

    @Override
    public void close() {
        super.close();
        if (targetVector != null) {
            targetVector.close();
        }
        if (equationVector != null) {
            equationVector.close();
        }
    }
}
