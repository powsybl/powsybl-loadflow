/*
 * Copyright (c) 2023-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.asym;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.ComplexPart;
import com.powsybl.openloadflow.util.Fortescue.SequenceType;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at gmail.com>}
 * @author Jean-Baptiste Heyberger {@literal <jbheyberger at gmail.com>}
 */
public class AsymmetricalAcEquationSystemCreator extends AcEquationSystemCreator {

    private static final double EPSILON = 0.00001;

    public AsymmetricalAcEquationSystemCreator(LfNetwork network, AcEquationSystemCreationParameters creationParameters) {
        super(network, creationParameters);
    }

    @Override
    protected void createBusEquation(LfBus bus, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        super.createBusEquation(bus, equationSystem);

        // addition of asymmetric equations, supposing that existing v, theta, p and q are linked to the positive sequence
        LfAsymBus asymBus = bus.getAsym();

        var ixh = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_ZERO);
        asymBus.setIxZ(ixh);
        var iyh = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_ZERO);
        asymBus.setIyZ(iyh);

        var ixi = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_NEGATIVE);
        asymBus.setIxN(ixi);
        var iyi = equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_NEGATIVE);
        asymBus.setIyN(iyi);

        // handle generators at bus for zero and negative
        for (LfGenerator gen : bus.getGenerators()) {
            // if there is at least one generating unit that is voltage controlling we model the equivalent in negative and zero
            // with a large admittance yg = g +jb to model a close connection of the bus to the ground (E_zero = 0 E_negative = 0)
            if (gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.REMOTE_REACTIVE_POWER
                    || gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.MONITORING_VOLTAGE) {
                throw new IllegalStateException("Generator with control type " + gen.getGeneratorControlType()
                        + " not yet supported in asymmetric load flow: " + gen.getId());
            }

            if (gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE) {
                LfAsymGenerator asymGen = gen.getAsym();
                if (asymGen != null) {
                    asymBus.setBzEquiv(asymBus.getBzEquiv() + asymGen.getBz());
                    asymBus.setGzEquiv(asymBus.getGzEquiv() + asymGen.getGz());
                    asymBus.setBnEquiv(asymBus.getBnEquiv() + asymGen.getBn());
                    asymBus.setGnEquiv(asymBus.getGnEquiv() + asymGen.getGn());
                }
            }
        }

        if (Math.abs(asymBus.getBzEquiv()) > EPSILON || Math.abs(asymBus.getGzEquiv()) > EPSILON) {
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_ZERO)
                    .addTerm(new ShuntFortescueIxEquationTerm(bus, equationSystem.getVariableSet(), SequenceType.ZERO));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_ZERO)
                    .addTerm(new ShuntFortescueIyEquationTerm(bus, equationSystem.getVariableSet(), SequenceType.ZERO));
        }

        if (Math.abs(asymBus.getGnEquiv()) > EPSILON || Math.abs(asymBus.getBnEquiv()) > EPSILON) {
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_NEGATIVE)
                    .addTerm(new ShuntFortescueIxEquationTerm(bus, equationSystem.getVariableSet(), SequenceType.NEGATIVE));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_NEGATIVE).
                    addTerm(new ShuntFortescueIyEquationTerm(bus, equationSystem.getVariableSet(), SequenceType.NEGATIVE));
        }

        if (Math.abs(bus.getLoadTargetP()) > EPSILON || Math.abs(bus.getLoadTargetQ()) > EPSILON) {
            // load modelled as a constant power load in abc phase representation leading to a model depending on vd, vi, vo in fortescue representation
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_ZERO)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.REAL, SequenceType.ZERO));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_P)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.REAL, SequenceType.POSITIVE));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IX_NEGATIVE)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.REAL, SequenceType.NEGATIVE));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_ZERO)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, SequenceType.ZERO));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_Q)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, SequenceType.POSITIVE));
            equationSystem.createEquation(bus, AcEquationType.BUS_TARGET_IY_NEGATIVE)
                    .addTerm(new LoadFortescuePowerEquationTerm(bus, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, SequenceType.NEGATIVE));
        }
    }

    @Override
    protected void createImpedantBranch(LfBranch branch, LfBus bus1, LfBus bus2, EquationSystem<AcVariableType,
            AcEquationType> equationSystem, AcVectorEngine acVectorEnginee) {
        // positive sequence
        EquationTerm<AcVariableType, AcEquationType> p1 = null;
        EquationTerm<AcVariableType, AcEquationType> q1 = null;
        EquationTerm<AcVariableType, AcEquationType> p2 = null;
        EquationTerm<AcVariableType, AcEquationType> q2 = null;
        EquationTerm<AcVariableType, AcEquationType> i1 = null;
        EquationTerm<AcVariableType, AcEquationType> i2 = null;

        // zero sequence
        EquationTerm<AcVariableType, AcEquationType> ixz1 = null;
        EquationTerm<AcVariableType, AcEquationType> iyz1 = null;
        EquationTerm<AcVariableType, AcEquationType> ixz2 = null;
        EquationTerm<AcVariableType, AcEquationType> iyz2 = null;

        // negative sequence
        EquationTerm<AcVariableType, AcEquationType> ixn1 = null;
        EquationTerm<AcVariableType, AcEquationType> iyn1 = null;
        EquationTerm<AcVariableType, AcEquationType> ixn2 = null;
        EquationTerm<AcVariableType, AcEquationType> iyn2 = null;

        boolean deriveA1 = isDeriveA1(branch, creationParameters);
        boolean deriveR1 = isDeriveR1(branch);

        if (bus1 != null && bus2 != null) {
            if (!branch.isAsymmetric()) {
                // no asymmetry is detected with this line, we handle the equations as decoupled
                // positive
                p1 = new ClosedBranchSide1ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.POSITIVE, acVectorEnginee);
                q1 = new ClosedBranchSide1ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.POSITIVE, acVectorEnginee);
                p2 = new ClosedBranchSide2ActiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.POSITIVE, acVectorEnginee);
                q2 = new ClosedBranchSide2ReactiveFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.POSITIVE, acVectorEnginee);
                i1 = new ClosedBranchSide1CurrentMagnitudeEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, acVectorEnginee);
                i2 = new ClosedBranchSide2CurrentMagnitudeEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, acVectorEnginee);

                // zero
                ixz1 = new ClosedBranchI1xFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.ZERO, acVectorEnginee);
                iyz1 = new ClosedBranchI1yFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.ZERO, acVectorEnginee);
                ixz2 = new ClosedBranchI2xFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.ZERO, acVectorEnginee);
                iyz2 = new ClosedBranchI2yFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.ZERO, acVectorEnginee);

                // negative
                ixn1 = new ClosedBranchI1xFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.NEGATIVE, acVectorEnginee);
                iyn1 = new ClosedBranchI1yFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.NEGATIVE, acVectorEnginee);
                ixn2 = new ClosedBranchI2xFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.NEGATIVE, acVectorEnginee);
                iyn2 = new ClosedBranchI2yFlowEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), deriveA1, deriveR1, SequenceType.NEGATIVE, acVectorEnginee);
            } else {
                // assymmetry is detected with this line, we handle the equations as coupled between the different sequences
                // positive
                p1 = new AsymmetricalClosedBranchCoupledPowerEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.ONE, SequenceType.POSITIVE);
                q1 = new AsymmetricalClosedBranchCoupledPowerEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.ONE, SequenceType.POSITIVE);
                p2 = new AsymmetricalClosedBranchCoupledPowerEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.TWO, SequenceType.POSITIVE);
                q2 = new AsymmetricalClosedBranchCoupledPowerEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.TWO, SequenceType.POSITIVE);

                // zero
                ixz1 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.ONE, SequenceType.ZERO);
                iyz1 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.ONE, SequenceType.ZERO);
                ixz2 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.TWO, SequenceType.ZERO);
                iyz2 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.TWO, SequenceType.ZERO);

                // negative
                ixn1 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.ONE, SequenceType.NEGATIVE);
                iyn1 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.ONE, SequenceType.NEGATIVE);
                ixn2 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.REAL, TwoSides.TWO, SequenceType.NEGATIVE);
                iyn2 = new AsymmetricalClosedBranchCoupledCurrentEquationTerm(branch, bus1, bus2, equationSystem.getVariableSet(), ComplexPart.IMAGINARY, TwoSides.TWO, SequenceType.NEGATIVE);
            }
        } else if (bus1 != null) {
            throw new IllegalStateException("Line open at one side not yet supported in asymmetric load flow at bus: " + bus1.getId());
        } else if (bus2 != null) {
            throw new IllegalStateException("Line open at one side not yet supported in asymmetric load flow  at bus: " + bus2.getId());
        }

        // positive
        createImpedantBranchEquations(branch, bus1, bus2, equationSystem, p1, q1, i1, p2, q2, i2, p1, q1, i1, p2, q2, i2, null, null, null, null, null, null);

        // zero
        if (ixz1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_IX_ZERO)
                    .orElseThrow()
                    .addTerm(ixz1);
        }
        if (iyz1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_IY_ZERO)
                    .orElseThrow()
                    .addTerm(iyz1);
        }
        if (ixz2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_IX_ZERO)
                    .orElseThrow()
                    .addTerm(ixz2);
        }
        if (iyz2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_IY_ZERO)
                    .orElseThrow()
                    .addTerm(iyz2);
        }

        // negative
        if (ixn1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_IX_NEGATIVE)
                    .orElseThrow()
                    .addTerm(ixn1);
        }
        if (iyn1 != null) {
            equationSystem.getEquation(bus1.getNum(), AcEquationType.BUS_TARGET_IY_NEGATIVE)
                    .orElseThrow()
                    .addTerm(iyn1);
        }
        if (ixn2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_IX_NEGATIVE)
                    .orElseThrow()
                    .addTerm(ixn2);
        }
        if (iyn2 != null) {
            equationSystem.getEquation(bus2.getNum(), AcEquationType.BUS_TARGET_IY_NEGATIVE)
                    .orElseThrow()
                    .addTerm(iyn2);
        }

        createGeneratorReactivePowerControlBranchEquation(branch, bus1, bus2, equationSystem, deriveA1, deriveR1, acVectorEnginee);

        createTransformerPhaseControlEquations(branch, bus1, bus2, equationSystem, deriveA1, deriveR1, acVectorEnginee);

        createTransformerReactivePowerControlEquations(branch, equationSystem);
    }
}
