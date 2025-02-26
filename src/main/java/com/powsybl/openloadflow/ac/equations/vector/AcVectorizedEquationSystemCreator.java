/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcVectorizedEquationSystemCreator extends AcEquationSystemCreator {

    public AcVectorizedEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        super(network, creationParameters);
    }

    private AcShuntVector getShuntVector(AcEquationSystemCreationContext creationContext) {
        return ((AcVectorizedEquationSystemCreationContext) creationContext).getNetworkVector().getShuntVector();
    }

    private AcBranchVector getBranchVector(AcEquationSystemCreationContext creationContext) {
        return ((AcVectorizedEquationSystemCreationContext) creationContext).getNetworkVector().getBranchVector();
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createShuntCompensatorActiveFlowEquationTerm(LfShunt shunt, LfBus bus, AcEquationSystemCreationContext creationContext) {
        return new ShuntVectorCompensatorActiveFlowEquationTerm(getShuntVector(creationContext), shunt.getNum(), bus.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createShuntCompensatorReactiveFlowEquationTerm(LfShunt shunt, LfBus bus, boolean deriveB, AcEquationSystemCreationContext creationContext) {
        return new ShuntVectorCompensatorReactiveFlowEquationTerm(getShuntVector(creationContext), shunt.getNum(), bus.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveB);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide1ActiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide1ReactiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide1CurrentMagnitudeEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide2ActiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide2ReactiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createClosedBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, LfBus bus2, boolean deriveA1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new ClosedBranchVectorSide2CurrentMagnitudeEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveA1, deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ActiveFlowEquationTerm(LfBranch branch, LfBus bus1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide2ActiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2ReactiveFlowEquationTerm(LfBranch branch, LfBus bus1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide2ReactiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide2CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus1, boolean deriveR1, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide2CurrentMagnitudeEquationTerm(getBranchVector(creationContext), branch.getNum(), bus1.getNum(), creationContext.getEquationSystem().getVariableSet(), deriveR1);
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ActiveFlowEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide1ActiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1ReactiveFlowEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide1ReactiveFlowEquationTerm(getBranchVector(creationContext), branch.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    protected EquationTerm<AcVariableType, AcEquationType> createOpenBranchSide1CurrentMagnitudeEquationTerm(LfBranch branch, LfBus bus2, AcEquationSystemCreationContext creationContext) {
        return new OpenBranchVectorSide1CurrentMagnitudeEquationTerm(getBranchVector(creationContext), branch.getNum(), bus2.getNum(), creationContext.getEquationSystem().getVariableSet());
    }

    @Override
    public EquationSystem<AcVariableType, AcEquationType> create() {
        EquationSystem<AcVariableType, AcEquationType> equationSystem = new EquationSystem<>();
        AcNetworkVector networkVector = new AcNetworkVector(network, equationSystem, creationParameters);
        AcVectorizedEquationSystemCreationContext creationContext = new AcVectorizedEquationSystemCreationContext(equationSystem, networkVector);
        create(creationContext);
        networkVector.startListening();
        return equationSystem;
    }
}
