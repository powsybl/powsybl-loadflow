/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfShunt;

import java.util.Collection;
import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public final class AdmittanceEquationSystem {

    private static final double B_EPSILON = 1e-8;

    private final EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem;

    private AdmittanceEquationSystem(EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
    }

    public EquationSystem<AdmittanceVariableType, AdmittanceEquationType> getEquationSystem() {
        return equationSystem;
    }

    //Equations are created based on the branches connections
    private static void createBranchEquation(VariableSet<AdmittanceVariableType> variableSet, EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem,
                                             LfBranch branch, LfBus bus1, LfBus bus2) {
        if (bus1 != null && bus2 != null) {
            // Equation system Y*V = I (expressed in cartesian coordinates x,y)
            equationSystem.createEquation(bus1.getNum(), AdmittanceEquationType.BUS_ADM_IX)
                    .addTerm(new AdmittanceEquationTermBranchI1x(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus1.getNum(), AdmittanceEquationType.BUS_ADM_IY)
                    .addTerm(new AdmittanceEquationTermBranchI1y(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus2.getNum(), AdmittanceEquationType.BUS_ADM_IX)
                    .addTerm(new AdmittanceEquationTermBranchI2x(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus2.getNum(), AdmittanceEquationType.BUS_ADM_IY)
                    .addTerm(new AdmittanceEquationTermBranchI2y(branch, bus1, bus2, variableSet));
        }
    }

    private static void createBranchEquations(Collection<LfBranch> branches, VariableSet<AdmittanceVariableType> variableSet, EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem) {
        for (LfBranch branch : branches) {
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            createBranchEquation(variableSet, equationSystem, branch, bus1, bus2);
        }
    }

    private static double getShuntB(LfBus bus) {
        LfShunt shunt = bus.getShunt().orElse(null);
        double b = 0;
        if (shunt != null) {
            b += shunt.getB();
        }
        LfShunt controllerShunt = bus.getControllerShunt().orElse(null);
        if (controllerShunt != null) {
            b += controllerShunt.getB();
        }
        return b;
    }

    private static void createShuntEquations(Collection<LfBus> buses, VariableSet<AdmittanceVariableType> variableSet, EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem) {
        for (LfBus bus : buses) {
            double b = getShuntB(bus);
            if (Math.abs(b) > B_EPSILON) {
                equationSystem.createEquation(bus.getNum(), AdmittanceEquationType.BUS_ADM_IX)
                        .addTerm(new AdmittanceEquationTermShunt(bus, variableSet, 0, b, true));
                equationSystem.createEquation(bus.getNum(), AdmittanceEquationType.BUS_ADM_IY)
                        .addTerm(new AdmittanceEquationTermShunt(bus, variableSet, 0, b, false));
            }
        }
    }

    public static AdmittanceEquationSystem create(LfNetwork network, VariableSet<AdmittanceVariableType> variableSet) {
        return create(network.getBuses(), network.getBranches(), variableSet);
    }

    public static AdmittanceEquationSystem create(Collection<LfBus> buses, Collection<LfBranch> branches, VariableSet<AdmittanceVariableType> variableSet) {
        EquationSystem<AdmittanceVariableType, AdmittanceEquationType> equationSystem = new EquationSystem<>();

        createBranchEquations(branches, variableSet, equationSystem);
        createShuntEquations(buses, variableSet, equationSystem);

        return new AdmittanceEquationSystem(equationSystem);
    }
}
