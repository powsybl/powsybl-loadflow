/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import gnu.trove.list.array.TDoubleArrayList;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.interfaces.SpanningTreeAlgorithm;
import org.jgrapht.alg.spanning.KruskalMinimumSpanningTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
        BUS_ZERO("bus_z", ElementType.BUS),
        BRANCH_ZERO("branch_z", ElementType.BRANCH);

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
        BUS_V("v", ElementType.BUS),
        DUMMY_V("dummy_v", ElementType.BRANCH);

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

        private static Map<LfBus, List<LfBranch>> findNeighbors(LfBus bus) {
            List<LfBranch> branches = bus.getBranches();

            // detect parallel branches
            Map<LfBus, List<LfBranch>> neighbors = new LinkedHashMap<>(branches.size());
            for (LfBranch branch : branches) {
                if (isConnected(branch) && !isZeroImpedanceBranch(branch)) {
                    LfBus otherBus = branch.getBus1() == bus ? branch.getBus2() : branch.getBus1();
                    neighbors.computeIfAbsent(otherBus, k -> new ArrayList<>())
                            .add(branch);
                }
            }
            if (neighbors.isEmpty()) { // should never happen
                throw new PowsyblException("Isolated bus");
            }

            return neighbors;
        }

        public InitVmBusEquationTerm(LfBus bus, VariableSet<InitVmVariableType> variableSet) {
            super(bus);

            Map<LfBus, List<LfBranch>> neighbors = findNeighbors(bus);

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
            return "v_distr";
        }
    }

    private final MatrixFactory matrixFactory;

    public VoltageMagnitudeInitializer(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    private static void initTarget(Equation<InitVmVariableType, InitVmEquationType> equation, LfNetwork network, double[] targets) {
        switch (equation.getType()) {
            case BUS_TARGET_V:
                int busNum = equation.getData() == null ? equation.getNum() : (Integer) equation.getData();
                LfBus bus = network.getBus(busNum);
                targets[equation.getColumn()] = bus.getVoltageControl().orElseThrow().getTargetValue();
                break;
            case BUS_ZERO:
            case BRANCH_ZERO:
                targets[equation.getColumn()] = 0;
                break;
            default:
                throw new IllegalStateException("Unknown equation type: " + equation.getType());
        }
    }

    private static boolean isZeroImpedanceBranch(LfBranch branch) {
        return branch.getPiModel().getX() == 0;
    }

    private static boolean isConnected(LfBranch branch) {
        return branch.getBus1() != null
                && branch.getBus2() != null;
    }

    private void findPvBusFriends(LfNetwork network, Map<LfBus, LfBus> pvBusFriends, Set<LfBranch> essentialZeroImpedanceBranches) {
        // check if there is at least one zero impedance branch
        boolean hasAtLeastOneZeroImpedanceBranch = network.getBranches().stream()
                .anyMatch(branch -> isConnected(branch) && isZeroImpedanceBranch(branch));

        if (hasAtLeastOneZeroImpedanceBranch) {
            var subGraph = network.createSubGraph(branch -> isConnected(branch) && isZeroImpedanceBranch(branch));
            List<Set<LfBus>> connectedSets = new ConnectivityInspector<>(subGraph).connectedSets();
            for (Set<LfBus> connectedSet : connectedSets) {
                // at this stage we consider that voltage target is consistent if several bus are controlling voltage
                // on this zero impedance connected set (fix done at network loading)
                LfBus pvBus = connectedSet.stream().filter(LfBus::isVoltageControlled).findFirst().orElse(null);
                if (pvBus != null) {
                    for (LfBus bus : connectedSet) {
                        if (bus != pvBus) {
                            pvBusFriends.put(bus, pvBus);
                        }
                    }
                }
            }
            SpanningTreeAlgorithm.SpanningTree<LfBranch> spanningTree = new KruskalMinimumSpanningTree<>(subGraph).getSpanningTree();
            essentialZeroImpedanceBranches.addAll(spanningTree.getEdges());
        }
    }

    private void createVoltageCouplingEquations(LfNetwork network, EquationSystem<InitVmVariableType, InitVmEquationType> equationSystem,
                                                Set<LfBranch> essentialZeroImpedanceBranches) {
        for (LfBranch branch : network.getBranches()) {
            if (isConnected(branch) && isZeroImpedanceBranch(branch) && essentialZeroImpedanceBranches.contains(branch)) {
                LfBus bus1 = branch.getBus1();
                LfBus bus2 = branch.getBus2();
                if (branch.getPiModel().getR1() != 1) {
                    throw new PowsyblException("Zero impedance transformer not implemented");
                }
                // 0 = v1 - v2
                EquationTerm<InitVmVariableType, InitVmEquationType> v1 = EquationTerm.createVariableTerm(bus1, InitVmVariableType.BUS_V, equationSystem.getVariableSet());
                EquationTerm<InitVmVariableType, InitVmEquationType> v2 = EquationTerm.createVariableTerm(bus2, InitVmVariableType.BUS_V, equationSystem.getVariableSet());
                equationSystem.createEquation(branch.getNum(), InitVmEquationType.BRANCH_ZERO)
                        .addTerm(v1)
                        .addTerm(EquationTerm.multiply(v2, -1));

                // to keep a square matrix, we have to add a dummy voltage variable at each side of the branch with a
                // opposite sign
                // as v1 = v2 whatever the dummy voltage variable is, the equation system solution won't be impacted
                EquationTerm<InitVmVariableType, InitVmEquationType> dummyV = EquationTerm.createVariableTerm(branch, InitVmVariableType.DUMMY_V, equationSystem.getVariableSet());
                equationSystem.getEquation(bus1.getNum(), InitVmEquationType.BUS_ZERO)
                        .or(() -> equationSystem.getEquation(bus1.getNum(), InitVmEquationType.BUS_TARGET_V))
                        .orElseThrow()
                        .addTerm(dummyV);
                equationSystem.getEquation(bus2.getNum(), InitVmEquationType.BUS_ZERO)
                        .or(() -> equationSystem.getEquation(bus2.getNum(), InitVmEquationType.BUS_TARGET_V))
                        .orElseThrow()
                        .addTerm(EquationTerm.multiply(dummyV, -1));
            }
        }
    }

    @Override
    public void prepare(LfNetwork network) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        // bus to PV bus mapping in case of a bus connected to a PV bus though a zero impedance sub network
        Map<LfBus, LfBus> pvBusFriends = new HashMap<>();
        Set<LfBranch> essentialZeroImpedanceBranches = new HashSet<>();
        findPvBusFriends(network, pvBusFriends, essentialZeroImpedanceBranches);

        // create the equation system:
        //
        // there are 2 types of equations:
        //   for PV buses: target_v = v_i where i is the bus number
        //   for other buses: 0 = sum_j(rho_i_j * b_j * v_j) / sum_j(b_j) - v_i where j are buses neighbors of bus i and b is 1 / x
        //
        // so the aim is to find a voltage plan that respect voltage set points and that computes other voltages
        // magnitude by interpolating neighbors bus values proportionally to branch susceptance and voltage ratio
        //
        EquationSystem<InitVmVariableType, InitVmEquationType> equationSystem = new EquationSystem<>();
        for (LfBus bus : network.getBuses()) {
            EquationTerm<InitVmVariableType, InitVmEquationType> v = EquationTerm.createVariableTerm(bus, InitVmVariableType.BUS_V, equationSystem.getVariableSet());
            if (bus.isVoltageControlled()) {
                equationSystem.createEquation(bus.getNum(), InitVmEquationType.BUS_TARGET_V)
                        .addTerm(v);
            } else {
                LfBus pvBusFriend = pvBusFriends.get(bus);
                if (pvBusFriend != null) {
                    var eq = equationSystem.createEquation(bus.getNum(), InitVmEquationType.BUS_TARGET_V)
                            .addTerm(v);
                    eq.setData(pvBusFriend.getNum()); // store the bus where to take the voltage target
                } else {
                    long impedantBranchCount = bus.getBranches().stream()
                            .filter(branch -> isConnected(branch) && !isZeroImpedanceBranch(branch))
                            .count();
                    var eq = equationSystem.createEquation(bus.getNum(), InitVmEquationType.BUS_ZERO);
                    if (impedantBranchCount > 0) {
                        eq.addTerm(new InitVmBusEquationTerm(bus, equationSystem.getVariableSet()))
                                .addTerm(EquationTerm.multiply(v, -1));
                    }
                }
            }

        }

        // create voltage coupling equation for each zero impedance branches
        createVoltageCouplingEquations(network, equationSystem, essentialZeroImpedanceBranches);

        try (JacobianMatrix<InitVmVariableType, InitVmEquationType> j = new JacobianMatrix<>(equationSystem, matrixFactory)) {
            double[] targets = TargetVector.createArray(network, equationSystem, VoltageMagnitudeInitializer::initTarget);

            j.solveTransposed(targets);

            for (Variable<InitVmVariableType> variable : equationSystem.getSortedVariablesToFind()) {
                if (variable.getType() == InitVmVariableType.BUS_V) {
                    LfBus bus = network.getBus(variable.getNum());
                    bus.setV(() -> targets[variable.getRow()]);
                }
            }
        }

        stopwatch.stop();
        LOGGER.info("Initial voltage magnitude solved in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
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
