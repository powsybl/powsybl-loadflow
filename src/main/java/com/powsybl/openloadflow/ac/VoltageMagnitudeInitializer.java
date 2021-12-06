/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.PowsyblException;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import gnu.trove.list.array.TDoubleArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This voltage initializer is able to find a voltage magnitude starting point by resolving a linear system
 * using only voltage set points, branches reactance and branches voltage ratio.
 * This initializer is particularly useful for cases with a large range of voltage (many transformers with a ratio far
 * from 1pu for instance).
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class VoltageMagnitudeInitializer implements VoltageInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoltageMagnitudeInitializer.class);

    public enum InitVmEquationType implements Quantity {
        BUS_TARGET_V("v", ElementType.BUS),
        BUS_ZERO("z", ElementType.BUS);

        private final String symbol;

        private final ElementType elementType;

        InitVmEquationType(String symbol, ElementType elementType) {
            this.symbol = symbol;
            this.elementType = elementType;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }

        @Override
        public ElementType getElementType() {
            return elementType;
        }
    }

    public enum InitVmVariableType implements Quantity {
        BUS_V("v", ElementType.BUS);

        private final String symbol;

        private final ElementType elementType;

        InitVmVariableType(String symbol, ElementType elementType) {
            this.symbol = symbol;
            this.elementType = elementType;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }

        @Override
        public ElementType getElementType() {
            return elementType;
        }
    }

    public static final class InitVmBusEquationTerm extends AbstractBusEquationTerm<InitVmVariableType, InitVmEquationType> {

        private final List<Variable<InitVmVariableType>> variables;

        private final TDoubleArrayList der;

        public InitVmBusEquationTerm(LfBus bus, VariableSet<InitVmVariableType> variableSet) {
            super(bus);

            // detect parallel branches
            List<LfBranch> branches = bus.getBranches();
            Map<LfBus, List<LfBranch>> neighbors = new LinkedHashMap<>(branches.size());
            for (LfBranch branch : branches) {
                LfBus otherBus = branch.getBus1() == bus ? branch.getBus2() : branch.getBus1();
                if (otherBus != null) {
                    neighbors.computeIfAbsent(otherBus, k -> new ArrayList<>())
                            .add(branch);
                }
            }
            if (neighbors.isEmpty()) { // should never happen
                throw new PowsyblException("Isolated bus");
            }

            variables = new ArrayList<>(neighbors.size());
            der = new TDoubleArrayList(neighbors.size());
            double bs = 0; // neighbor branches susceptance sum
            for (Map.Entry<LfBus, List<LfBranch>> e : neighbors.entrySet()) {
                LfBus neighborBus = e.getKey();
                List<LfBranch> neighborBranches = e.getValue();

                // in case of multiple branches connected to same buses, we just sum the susceptance and take the
                // average voltage ratio.
                double b = 0;
                double r = 0;
                for (LfBranch neighborBranch : neighborBranches) {
                    PiModel piModel = neighborBranch.getPiModel();
                    b += Math.abs(1 / piModel.getX()); // to void issue with negative reactances
                    r += neighborBranch.getBus1() == bus ? 1 / piModel.getR1() : piModel.getR1();
                }
                r /= neighborBranches.size();

                bs += b;
                der.add(b * r);

                // add variable
                variables.add(variableSet.getVariable(neighborBus.getNum(), InitVmVariableType.BUS_V));
            }
            if (bs == 0) { // should never happen
                throw new PowsyblException("Susceptance sum is zero");
            }
            for (int i = 0; i < der.size(); i++) {
                der.setQuick(i, der.getQuick(i) / bs);
            }
        }

        @Override
        public List<Variable<InitVmVariableType>> getVariables() {
            return variables;
        }

        @Override
        public void update(double[] x) {
            // nothing that depends on state
        }

        @Override
        public double eval() {
            throw new IllegalStateException("Useless");
        }

        @Override
        public double der(Variable<InitVmVariableType> variable) {
            int i = variables.indexOf(variable);
            if (i == -1) {
                throw new IllegalStateException("Unknown variable: " + variable);
            }
            return der.getQuick(i);
        }

        @Override
        protected String getName() {
            return "v";
        }
    }

    private final MatrixFactory matrixFactory;

    public VoltageMagnitudeInitializer(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    private static void initTarget(Equation<InitVmVariableType, InitVmEquationType> equation, LfNetwork network, double[] targets) {
        switch (equation.getType()) {
            case BUS_TARGET_V:
                LfBus bus = network.getBus(equation.getNum());
                targets[equation.getColumn()] = bus.getVoltageControl().orElseThrow().getTargetValue();
                break;
            case BUS_ZERO:
                targets[equation.getColumn()] = 0;
                break;
            default:
                throw new IllegalStateException("Unknown equation type: " + equation.getType());
        }
    }

    @Override
    public void prepare(LfNetwork network) {
        // create the equation system:
        //
        // there are 2 types of equations:
        //   for PV buses: target_v = v_i where i is the bus number
        //   for other buses: 0 = sum_j(b_j * v_j) / sum_j(b_j) - v_i where j are buses neighbors of bus i and b is 1 / x
        //
        // so the aim is to find a voltage plan that respect voltage set points and that computes other voltages
        // magnitude by interpolating neighbors bus values proportionally to branch susceptance and voltage ratio
        //
        EquationSystem<InitVmVariableType, InitVmEquationType> equationSystem = new EquationSystem<>();
        VariableSet<InitVmVariableType> variableSet = new VariableSet<>();
        for (LfBus bus : network.getBuses()) {
            EquationTerm<InitVmVariableType, InitVmEquationType> v = EquationTerm.createVariableTerm(bus, InitVmVariableType.BUS_V, variableSet);
            if (bus.isVoltageControlled()) {
                equationSystem.createEquation(bus.getNum(), InitVmEquationType.BUS_TARGET_V)
                        .addTerm(v);
            } else {
                equationSystem.createEquation(bus.getNum(), InitVmEquationType.BUS_ZERO)
                        .addTerm(new InitVmBusEquationTerm(bus, variableSet))
                        .addTerm(EquationTerm.multiply(v, -1));
            }
        }

        try (JacobianMatrix<InitVmVariableType, InitVmEquationType> j = new JacobianMatrix<>(equationSystem, matrixFactory)) {
            double[] targets = TargetVector.createArray(network, equationSystem, VoltageMagnitudeInitializer::initTarget);

            j.solveTransposed(targets);

            for (Variable<InitVmVariableType> variable : equationSystem.getSortedVariablesToFind()) {
                LfBus bus = network.getBus(variable.getNum());
                bus.setV(() -> targets[variable.getRow()]);
            }
        }

        LOGGER.info("Initial voltage magnitude solved");
    }

    @Override
    public double getMagnitude(LfBus bus) {
        return bus.getV().eval();
    }

    @Override
    public double getAngle(LfBus bus) {
        return 0;
    }
}
