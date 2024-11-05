/**
 * Copyright (c) 2022, Jean-Baptiste Heyberger & Geoffroy Jamgotchian
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jbheyberger at gmail.com>
 */
public final class AdmittanceEquationSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdmittanceEquationSystem.class);

    private static final double B_EPSILON = 0.00000001;

    private final EquationSystem<VariableType, EquationType> equationSystem;

    private AdmittanceEquationSystem(EquationSystem<VariableType, EquationType> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
    }

    public EquationSystem<VariableType, EquationType> getEquationSystem() {
        return equationSystem;
    }

    //Equations are created based on the branches connections
    private static void createImpedantBranch(VariableSet<VariableType> variableSet, EquationSystem<VariableType, EquationType> equationSystem,
                                             LfBranch branch, LfBus bus1, LfBus bus2) {
        if (bus1 != null && bus2 != null) {
            // Equation system Y*V = I (expressed in cartesian coordinates x,y)
            equationSystem.createEquation(bus1.getNum(), EquationType.BUS_YR)
                    .addTerm(new AdmittanceEquationTermBranchX1(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus1.getNum(), EquationType.BUS_YI)
                    .addTerm(new AdmittanceEquationTermBranchY1(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus2.getNum(), EquationType.BUS_YR)
                    .addTerm(new AdmittanceEquationTermBranchX2(branch, bus1, bus2, variableSet));

            equationSystem.createEquation(bus2.getNum(), EquationType.BUS_YI)
                    .addTerm(new AdmittanceEquationTermBranchY2(branch, bus1, bus2, variableSet));
        }
    }

    private static void createBranches(Collection<LfBranch> branches, VariableSet<VariableType> variableSet, EquationSystem<VariableType, EquationType> equationSystem) {
        for (LfBranch branch : branches) {
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            PiModel piModel = branch.getPiModel();
            if (FastMath.abs(piModel.getX()) < LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE) {
                if (bus1 != null && bus2 != null) {
                    LOGGER.warn("Non impedant branches ({}w) not supported in admittance matrix",
                            branch.getId());
                }
            } else {
                createImpedantBranch(variableSet, equationSystem, branch, bus1, bus2);
            }
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

    private static void createShunts(Collection<LfBus> buses, VariableSet<VariableType> variableSet, EquationSystem<VariableType, EquationType> equationSystem) {
        for (LfBus bus : buses) {
            double b = getShuntB(bus);
            if (Math.abs(b) > B_EPSILON) {
                equationSystem.createEquation(bus.getNum(), EquationType.BUS_YR)
                        .addTerm(new AdmittanceEquationTermShunt(bus, variableSet, 0, b, true));
                equationSystem.createEquation(bus.getNum(), EquationType.BUS_YI)
                        .addTerm(new AdmittanceEquationTermShunt(bus, variableSet, 0, b, false));
            }
        }
    }

    public static AdmittanceEquationSystem create(LfNetwork network, VariableSet<VariableType> variableSet) {
        return create(network.getBuses(), network.getBranches(), variableSet);
    }

    public static AdmittanceEquationSystem create(Collection<LfBus> buses, Collection<LfBranch> branches, VariableSet<VariableType> variableSet) {
        EquationSystem<VariableType, EquationType> equationSystem = new EquationSystem<>();

        createBranches(branches, variableSet, equationSystem);
        createShunts(buses, variableSet, equationSystem);

        return new AdmittanceEquationSystem(equationSystem);
    }
}