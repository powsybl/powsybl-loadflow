/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.nr;

import com.powsybl.math.matrix.MatrixException;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class NewtonRaphson {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewtonRaphson.class);

    private final LfNetwork network;

    private final NewtonRaphsonParameters parameters;

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    private int iteration = 0;

    private final JacobianMatrix<AcVariableType, AcEquationType> j;

    private final TargetVector<AcVariableType, AcEquationType> targetVector;

    private final EquationVector<AcVariableType, AcEquationType> equationVector;

    public NewtonRaphson(LfNetwork network, NewtonRaphsonParameters parameters,
                         EquationSystem<AcVariableType, AcEquationType> equationSystem,
                         JacobianMatrix<AcVariableType, AcEquationType> j,
                         TargetVector<AcVariableType, AcEquationType> targetVector,
                         EquationVector<AcVariableType, AcEquationType> equationVector) {
        this.network = Objects.requireNonNull(network);
        this.parameters = Objects.requireNonNull(parameters);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        this.j = Objects.requireNonNull(j);
        this.targetVector = Objects.requireNonNull(targetVector);
        this.equationVector = Objects.requireNonNull(equationVector);
    }

    public static List<Pair<Equation<AcVariableType, AcEquationType>, Double>> findLargestMismatches(EquationSystem<AcVariableType, AcEquationType> equationSystem, double[] mismatch, int count) {
        return equationSystem.getIndex().getSortedEquationsToSolve().stream()
                .map(equation -> Pair.of(equation, mismatch[equation.getColumn()]))
                .filter(e -> Math.abs(e.getValue()) > Math.pow(10, -7))
                .sorted(Comparator.comparingDouble((Map.Entry<Equation<AcVariableType, AcEquationType>, Double> e) -> Math.abs(e.getValue())).reversed())
                .limit(count)
                .collect(Collectors.toList());
    }

    private NewtonRaphsonStatus runIteration() {
        LOGGER.debug("Start iteration {}", iteration);

        try {
            // solve f(x) = j * dx
            try {
                j.solveTransposed(equationVector.getArray());
            } catch (MatrixException e) {
                LOGGER.error(e.toString(), e);
                return NewtonRaphsonStatus.SOLVER_FAILED;
            }
            // f(x) now contains dx

            // update x and f(x) will be automatically updated
            equationSystem.getStateVector().minus(equationVector.getArray());

            // substract targets from f(x)
            equationVector.minus(targetVector);
            // f(x) now contains equation mismatches

            if (LOGGER.isTraceEnabled()) {
                findLargestMismatches(equationSystem, equationVector.getArray(), 5)
                        .forEach(e -> {
                            Equation<AcVariableType, AcEquationType> equation = e.getKey();
                            String elementId = equation.getElement(network).map(LfElement::getId).orElse("?");
                            LOGGER.trace("Mismatch for {}: {} (element={})", equation, e.getValue(), elementId);
                        });
            }

            // test stopping criteria and log norm(fx)
            NewtonRaphsonStoppingCriteria.TestResult testResult = parameters.getStoppingCriteria().test(equationVector.getArray());

            LOGGER.debug("|f(x)|={}", testResult.getNorm());

            if (testResult.isStop()) {
                return NewtonRaphsonStatus.CONVERGED;
            }

            return null;
        } finally {
            iteration++;
        }
    }

    public static void initStateVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, VoltageInitializer initializer) {
        double[] x = new double[equationSystem.getIndex().getSortedVariablesToFind().size()];
        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            switch (v.getType()) {
                case BUS_V:
                    x[v.getRow()] = initializer.getMagnitude(network.getBus(v.getElementNum()));
                    break;

                case BUS_PHI:
                    x[v.getRow()] = Math.toRadians(initializer.getAngle(network.getBus(v.getElementNum())));
                    break;

                case SHUNT_B:
                    x[v.getRow()] = network.getShunt(v.getElementNum()).getB();
                    break;

                case BRANCH_ALPHA1:
                    x[v.getRow()] = network.getBranch(v.getElementNum()).getPiModel().getA1();
                    break;

                case BRANCH_RHO1:
                    x[v.getRow()] = network.getBranch(v.getElementNum()).getPiModel().getR1();
                    break;

                case DUMMY_P:
                case DUMMY_Q:
                    x[v.getRow()] = 0;
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type "  + v.getType());
            }
        }
        equationSystem.getStateVector().set(x);
    }

    public void updateNetwork() {
        // update state variable
        StateVector stateVector = equationSystem.getStateVector();
        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            switch (v.getType()) {
                case BUS_V:
                    network.getBus(v.getElementNum()).setV(stateVector.get(v.getRow()));
                    break;

                case BUS_PHI:
                    network.getBus(v.getElementNum()).setAngle(Math.toDegrees(stateVector.get(v.getRow())));
                    break;

                case SHUNT_B:
                    network.getShunt(v.getElementNum()).setB(stateVector.get(v.getRow()));
                    break;

                case BRANCH_ALPHA1:
                    network.getBranch(v.getElementNum()).getPiModel().setA1(stateVector.get(v.getRow()));
                    break;

                case BRANCH_RHO1:
                    network.getBranch(v.getElementNum()).getPiModel().setR1(stateVector.get(v.getRow()));
                    break;

                case DUMMY_P:
                case DUMMY_Q:
                    // nothing to do
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type "  + v.getType());
            }
        }
    }

    public NewtonRaphsonResult run(VoltageInitializer voltageInitializer) {

        // initialize state vector
        initStateVector(network, equationSystem, voltageInitializer);

        Vectors.minus(equationVector.getArray(), targetVector.getArray());

        // start iterations
        NewtonRaphsonStatus status = NewtonRaphsonStatus.NO_CALCULATION;
        while (iteration <= parameters.getMaxIteration()) {
            NewtonRaphsonStatus newStatus = runIteration();
            if (newStatus != null) {
                status = newStatus;
                break;
            }
        }

        if (iteration >= parameters.getMaxIteration()) {
            status = NewtonRaphsonStatus.MAX_ITERATION_REACHED;
        }

        // update network state variable
        if (status == NewtonRaphsonStatus.CONVERGED) {
            updateNetwork();
        }

        return new NewtonRaphsonResult(status, iteration, network.getSlackBus().getMismatchP());
    }
}
