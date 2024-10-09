/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.artelys.knitro.api.*;
import com.artelys.knitro.api.callbacks.*;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import net.jafama.DoubleWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.google.common.primitives.Doubles.toArray;

/**
 * @author Pierre Arvy {@literal <pierre.arvy at artelys.com>}
 * @author Jeanne Archambault {@literal <jeanne.archambault at artelys.com>}
 */
public class KnitroSolver extends AbstractNonLinearExternalSolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnitroSolver.class);

    protected static KnitroSolverParameters knitroParameters = new KnitroSolverParameters();

    public KnitroSolver(LfNetwork network, KnitroSolverParameters knitroParameters,
                           EquationSystem<AcVariableType, AcEquationType> equationSystem, JacobianMatrix<AcVariableType, AcEquationType> j,
                           TargetVector<AcVariableType, AcEquationType> targetVector, EquationVector<AcVariableType, AcEquationType> equationVector,
                           boolean detailedReport) {
        super(network, equationSystem, j, targetVector, equationVector, detailedReport);
        this.knitroParameters = knitroParameters;
    }

    @Override
    public String getName() {
        return "Knitro Solver";
    }

    // List of all possible Knitro status
    public enum KnitroStatus {
    CONVERGED_TO_LOCAL_OPTIMUM,
    CONVERGED_TO_FEASIBLE_APPROXIMATE_SOLUTION,
    TERMINATED_AT_INFEASIBLE_POINT,
    PROBLEM_UNBOUNDED,
    TERMINATED_DUE_TO_PRE_DEFINED_LIMIT,
    INPUT_OR_NON_STANDARD_ERROR }

    // Get AcStatus equivalent from Knitro Status, and log Knitro Status
    public AcSolverStatus getAcStatusAndKnitroStatus(int knitroStatus) {
        if (knitroStatus == 0) {
            logKnitroStatus(KnitroStatus.CONVERGED_TO_LOCAL_OPTIMUM);
            return AcSolverStatus.CONVERGED;
        } else if (isInRange(knitroStatus, -199, -100)) {
            logKnitroStatus(KnitroStatus.CONVERGED_TO_FEASIBLE_APPROXIMATE_SOLUTION);
            return AcSolverStatus.CONVERGED;
        } else if (isInRange(knitroStatus, -299, -200)) {
            logKnitroStatus(KnitroStatus.TERMINATED_AT_INFEASIBLE_POINT);
            return AcSolverStatus.SOLVER_FAILED;
        } else if (isInRange(knitroStatus, -399, -300)) {
            logKnitroStatus(KnitroStatus.PROBLEM_UNBOUNDED);
            return AcSolverStatus.SOLVER_FAILED;
        } else if (isInRange(knitroStatus, -499, -400)) {
            logKnitroStatus(KnitroStatus.TERMINATED_DUE_TO_PRE_DEFINED_LIMIT);
            return AcSolverStatus.MAX_ITERATION_REACHED;
        } else if (isInRange(knitroStatus, -599, -500)) {
            logKnitroStatus(KnitroStatus.INPUT_OR_NON_STANDARD_ERROR);
            return AcSolverStatus.NO_CALCULATION;
        } else {
            LOGGER.info("Knitro Status: unknown");
            throw new IllegalArgumentException("Unknown Knitro Status");
        }
    }

    private void logKnitroStatus(KnitroStatus status) {
        LOGGER.info("Knitro Status: {}", status);
    }

    private boolean isInRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static final class KnitroProblem extends KNProblem {
        /*------------------------------------------------------------------*/
        /*     FUNCTION callbackEvalF                                       */
        /*------------------------------------------------------------------*/

        private final class CallbackEvalFC extends KNEvalFCCallback {

            private final List<Equation<AcVariableType, AcEquationType>> sortedEquationsToSolve;
            private final LfNetwork lfNetwork;
            private final List<Integer> listNonLinearConsts;

            private CallbackEvalFC(List<Equation<AcVariableType, AcEquationType>> sortedEquationsToSolve, LfNetwork lfNetwork, List<Integer> listNonLinearConsts) {
                this.sortedEquationsToSolve = sortedEquationsToSolve;
                this.lfNetwork = lfNetwork;
                this.listNonLinearConsts = listNonLinearConsts;
            }

            // Callback function for non-linear parts of objective and constraints
            @Override
            public void evaluateFC(final List<Double> x, final List<Double> obj, final List<Double> c) {
                LOGGER.trace("============ Knitro evaluating callback function ============");

                // =============== Non-linear constraints in P and Q ===============

                // Update current state
                StateVector currentState = new StateVector(toArray(x));
                LOGGER.trace("Current state vector {}", currentState.get());
                LOGGER.trace("Evaluating {} non-linear constraints", listNonLinearConsts.size());

                // Add non-linear constraints
                int indexNonLinearCst = 0; // callback index of current constraint added
                for (int equationId : listNonLinearConsts) {
                    Equation<AcVariableType, AcEquationType> equation = sortedEquationsToSolve.get(equationId);
                    AcEquationType typeEq = equation.getType();
                    double valueConst = 0;
                    if (!SolverUtils.getNonLinearConstraintsTypes().contains(typeEq)) { // check that the constraint is really non-linear
                        throw new IllegalArgumentException("Equation of type " + typeEq + " is linear, and should be considered in the main function of Knitro, not in the callback function");
                    } else {
                        // we evaluate the equation with respect to the current state
                        for (EquationTerm term : equation.getTerms()) {
                            term.setStateVector(currentState);
                            if (term.isActive()) {
                                valueConst += term.eval();
                            }
                        }
                        try {
                            c.set(indexNonLinearCst, valueConst); // adding the constraint
                            LOGGER.trace("Adding non-linear constraint n° {}, of type {} and of value {}", equationId, typeEq, valueConst);
                        } catch (Exception e) {
                            throw new PowsyblException("Exception found while trying to add non-linear constraint n° " + equationId, e);
                        }
                    }
                    indexNonLinearCst += 1; // we move on to the next constraint
                }
            }
        }

        /*------------------------------------------------------------------*/
        /*     FUNCTION callbackEvalG                                       */
        /*------------------------------------------------------------------*/
        private final class CallbackEvalG extends KNEvalGACallback {
            private final JacobianMatrix<AcVariableType, AcEquationType> oldMatrix;
            private final List<Integer> listNonZerosCts;
            private final List<Integer> listNonZerosVars;
            private final List<Integer> listNonZerosCts2;
            private final List<Integer> listNonZerosVars2;
            private final List<Integer> listNonLinearConsts;
            private final List<Integer> listVarChecker;
            private final LfNetwork network;
            private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

            private CallbackEvalG(JacobianMatrix<AcVariableType, AcEquationType> oldMatrix, List<Integer> listNonZerosCts, List<Integer> listNonZerosVars, List<Integer> listNonZerosCts2, List<Integer> listNonZerosVars2, List<Integer> listNonLinearConsts, List<Integer> listVarChecker, LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
                this.oldMatrix = oldMatrix;
                this.listNonZerosCts = listNonZerosCts;
                this.listNonZerosVars = listNonZerosVars;
                this.listNonZerosCts2 = listNonZerosCts2;
                this.listNonZerosVars2 = listNonZerosVars2;
                this.listNonLinearConsts = listNonLinearConsts;
                this.listVarChecker = listVarChecker;
                this.network = network;
                this.equationSystem = equationSystem;
            }

            @Override
            public void evaluateGA(final List<Double> x, final List<Double> objGrad, final List<Double> jac) {
                // Update current Jacobian
                equationSystem.getStateVector().set(toArray(x));
                AcSolverUtil.updateNetwork(network, equationSystem);
                oldMatrix.forceUpdate();
                // For sparse matrix, get values, row and column structure
                SparseMatrix sparseOldMatrix = oldMatrix.getMatrix().toSparse();
                int[] columnStart = sparseOldMatrix.getColumnStart();
                int[] rowIndices = sparseOldMatrix.getRowIndices();
                double[] values = sparseOldMatrix.getValues();

                // Number of constraints evaluated in callback
                int numCbCts = 0;
                if (knitroParameters.getGradientUserRoutine() == 1) {
                    numCbCts = listNonZerosCts.size();
                } else if (knitroParameters.getGradientUserRoutine() == 2) {
                    numCbCts = listNonZerosCts2.size();
                }

                // Pass coefficients of Jacobian matrix to Knitro
                for (int index = 0; index < numCbCts; index++) {
                    try {
                        int var = 0;
                        int ct = 0;
                        if (knitroParameters.getGradientUserRoutine() == 1) {
                            var = listNonZerosVars.get(index);
                            ct = listNonZerosCts.get(index);
                        } else if (knitroParameters.getGradientUserRoutine() == 2) {
                            var = listNonZerosVars2.get(index);
                            ct = listNonZerosCts2.get(index);
                        }

                        // Start and end index in the values array for column ct
                        int colStart = columnStart[ct];
                        int colEnd = columnStart[ct + 1];
                        double valueSparse = 0.0;

                        // Iterate through the column range
                        for (int i = colStart; i < colEnd; i++) {
                            // Check if the row index matches var
                            if (rowIndices[i] == var) {
                                // Get the corresponding value
                                valueSparse = values[i];
                                break;  // Exit loop since the value is found
                            }
                        }
                        jac.set(index, valueSparse);
                    } catch (Exception e) {
                        LOGGER.error("Exception found while trying to add Jacobian term {} in non-linear constraint n° {}", listNonZerosVars2.get(index), listNonZerosCts2.get(index));
                        LOGGER.error(e.getMessage());
                    }
                }

//                List<Double> jac1 = new ArrayList<>();
//                List<Double> jac2 = new ArrayList<>();
//                List<Double> jacDiff = new ArrayList<>();
//
//                // JAC 1 all non linear constraints
//                for (int ct : listNonLinearConsts) {
//                    for (int var = 0; var < equationSystem.getVariableSet().getVariables().size(); var++) { //TODO CHANGER
//                        try {
//                            jac1.add(denseOldMatrix.get(var, ct));  // Jacobian needs to be transposed
//                        } catch (Exception e) {
//                            LOGGER.error("Exception found while trying to add Jacobian term {} in non-linear constraint n° {}", var, ct);
//                            LOGGER.error(e.getMessage());
//                            throw new PowsyblException("Exception found while trying to add Jacobian term in non-linear constraint");
//                        }
//                    }
//                }
//
//                // JAC 2 only non zeros
//                for (int index = 0; index < listNonZerosCts2.size(); index++) {
//                    try {
//                        double value = denseOldMatrix.get(listNonZerosVars2.get(index), listNonZerosCts2.get(index));
//                        jac2.add(value);  // Jacobian needs to be transposed
//                    } catch (Exception e) {
//                        LOGGER.error("Exception found while trying to add Jacobian term {} in non-linear constraint n° {}", listNonZerosVars2.get(index), listNonZerosCts2.get(index));
//                        LOGGER.error(e.getMessage());
//                        throw new PowsyblException("Exception found while trying to add Jacobian term in non-linear constraint");
//                    }
//                }

//                // JAC DIFF
//                int id2 = 0;
//                for (int i = 0; i < jac1.size(); i++) {
//                    if (listVarChecker.get(i) != -1) {
//                        jacDiff.add(jac1.get(i) - jac2.get(id2));
//                        id2 += 1;
//                    } else {
//                        jacDiff.add(jac1.get(i));
//                    }
//                }
//
//                for (double value : jacDiff) {
//                    if (value >= 0.00001) {
//                        System.out.println("Les deux Jacobiennes sont censées être équivalentes, mais elles le sont pas!!! ; erreur " + value);
//                    }
//                }
            }
        }

        /*------------------------------------------------------------------*/
        /*     FUNCTION callbackEvalH                                       */
        /*------------------------------------------------------------------*/
        private static class CallbackEvalH extends KNEvalHCallback {
            @Override
            public void evaluateH(final List<Double> x, final double sigma, final List<Double> lambda, List<Double> hess) {
                // TODO ?
            }
        }

        private KnitroProblem(LfNetwork lfNetwork, EquationSystem<AcVariableType, AcEquationType> equationSystem, TargetVector targetVector, VoltageInitializer voltageInitializer, JacobianMatrix<AcVariableType, AcEquationType> jacobianMatrix, KnitroSolverParameters knitroParameters) throws KNException {

            // =============== Variables ===============
            // Defining variables
            super(equationSystem.getVariableSet().getVariables().size(), equationSystem.getIndex().getSortedEquationsToSolve().size());
            int numVar = equationSystem.getVariableSet().getVariables().size();
            List<Variable<AcVariableType>> sortedVariables = equationSystem.getIndex().getSortedVariablesToFind(); // ordering variables
            LOGGER.info("Defining {} variables", numVar);

            // Types, bounds and initial states of variables
            // Types
            List<Integer> listVarTypes = new ArrayList<>(Collections.nCopies(numVar, KNConstants.KN_VARTYPE_CONTINUOUS));
            setVarTypes(listVarTypes);

            // Bounds
            List<Double> listVarLoBounds = new ArrayList<>(numVar);
            List<Double> listVarUpBounds = new ArrayList<>(numVar);
            double loBndV = knitroParameters.getMinRealisticVoltage();
            double upBndV = knitroParameters.getMaxRealisticVoltage();
            for (int var = 0; var < sortedVariables.size(); var++) {
                Enum<AcVariableType> typeVar = sortedVariables.get(var).getType();
                if (typeVar == AcVariableType.BUS_V) {
                    listVarLoBounds.add(loBndV);
                    listVarUpBounds.add(upBndV);
                } else {
                    listVarLoBounds.add(-KNConstants.KN_INFINITY);
                    listVarUpBounds.add(KNConstants.KN_INFINITY);
                }
            }
            setVarLoBnds(listVarLoBounds);
            setVarUpBnds(listVarUpBounds);

            // Initial state
            List<Double> listXInitial = new ArrayList<>(numVar);
            AcSolverUtil.initStateVector(lfNetwork, equationSystem, voltageInitializer); // Initialize state vector
            for (int i = 0; i < numVar; i++) {
                listXInitial.add(equationSystem.getStateVector().get(i));
            }
            setXInitial(listXInitial);
            LOGGER.info("Initialization of variables : type of initialization {}", voltageInitializer);

            // =============== Constraints ==============
            // ----- Active constraints -----
            // Get active constraints and order them in same order as targets
            List<Equation<AcVariableType, AcEquationType>> sortedEquationsToSolve = equationSystem.getIndex().getSortedEquationsToSolve();

            int numConst = sortedEquationsToSolve.size();
            List<Integer> listNonLinearConsts = new ArrayList<>(); // list of indexes of non-linear constraints
            LOGGER.info("Defining {} active constraints", numConst);

            // ----- Linear constraints -----
            for (int equationId = 0; equationId < numConst; equationId++) {
                Equation<AcVariableType, AcEquationType> equation = sortedEquationsToSolve.get(equationId);
                AcEquationType typeEq = equation.getType();
                List<EquationTerm<AcVariableType, AcEquationType>> terms = equation.getTerms();
                SolverUtils solverUtils = new SolverUtils();
                if (SolverUtils.getLinearConstraintsTypes().contains(typeEq)) {
                    List<Integer> listVar = new ArrayList<>();
                    List<Double> listCoef = new ArrayList<>();
                    listVar = solverUtils.getLinearConstraint(typeEq, equationId, terms).getListIdVar();
                    listCoef = solverUtils.getLinearConstraint(typeEq, equationId, terms).getListCoef();

                    for (int i = 0; i < listVar.size(); i++) {
                        addConstraintLinearPart(equationId, listVar.get(i), listCoef.get(i));
                    }
                    LOGGER.trace("Adding linear constraint n° {} of type {}", equationId, typeEq);

                } else {
                    // ----- Non-linear constraints -----
                    listNonLinearConsts.add(equationId); // Add constraint number to list of non-linear constraints
                }
            }

            // ----- Non-linear constraints -----
            // Callback
            setMainCallbackCstIndexes(listNonLinearConsts);

            // ----- RHS : targets -----
            setConEqBnds(Arrays.stream(targetVector.getArray()).boxed().toList());

            // =============== Objective ==============
            setObjConstPart(0.0);

            // =============== Callback ==============
            // ----- Constraints -----
            setObjEvalCallback(new CallbackEvalFC(sortedEquationsToSolve, lfNetwork, listNonLinearConsts));

            // ----- Jacobian matrix -----
            // Non zero pattern
            List<Integer> listNonZerosCts = new ArrayList<>(); //list of constraints to parse to Knitro non-zero pattern
            List<Integer> listNonZerosVars = new ArrayList<>(); //list of variables to pass to Knitro non-zero pattern
            List<Integer> listNonZerosCts2 = new ArrayList<>();
            List<Integer> listNonZerosVars2 = new ArrayList<>();
            List<Integer> listVarChecker = new ArrayList<>();

            // FIRST METHOD : all non-linear constraints
            if (knitroParameters.getGradientComputationMode() == 1) { // User routine to compute the Jacobian
                if (knitroParameters.getGradientUserRoutine() == 1) {
                    for (Integer idCt : listNonLinearConsts) {
                        for (int i = 0; i < numVar; i++) {
                            listNonZerosCts.add(idCt);
                        }
                    }

                    List<Integer> listVars = new ArrayList<>();
                    for (int i = 0; i < numVar; i++) {
                        listVars.add(i);
                    }
                    for (int i = 0; i < listNonLinearConsts.size(); i++) {
                        listNonZerosVars.addAll(listVars);
                    }
                } else if (knitroParameters.getGradientUserRoutine() == 2) {
                    // SECOND METHOD : only non-zero constraints
                    for (Integer ct : listNonLinearConsts) {
                        Equation<AcVariableType, AcEquationType> equation = sortedEquationsToSolve.get(ct);
                        List<EquationTerm<AcVariableType, AcEquationType>> terms = equation.getTerms();
                        List<Integer> listNonZerosVarsCurrentCt = new ArrayList<>(); //list of variables involved in current constraint

                        for (EquationTerm<AcVariableType, AcEquationType> term : terms) {
                            for (Variable variable : term.getVariables()) {
                                listNonZerosVarsCurrentCt.add(variable.getRow());
                            }
                        }
                        List<Integer> uniqueListVarsCurrentCt = listNonZerosVarsCurrentCt.stream().distinct().sorted().toList(); // remove duplicate elements from the list
                        listNonZerosVars2.addAll(uniqueListVarsCurrentCt);

                        //                for (int var = 0; var < sortedVariables.size(); var++) {
                        //                    if (uniqueListVarsCurrentCt.contains(var)) {
                        //                        listVarChecker.add(var);
                        //                    } else {
                        //                        listVarChecker.add(-1);
                        //                    }
                        //                }

                        listNonZerosCts2.addAll(new ArrayList<>(Collections.nCopies(uniqueListVarsCurrentCt.size(), ct)));
                    }
                }
            }

            if (knitroParameters.getGradientComputationMode() == 1) { // User routine to compute the Jacobian
                if (knitroParameters.getGradientUserRoutine() == 1) {
                    setJacNnzPattern(listNonZerosCts, listNonZerosVars);
                } else if (knitroParameters.getGradientUserRoutine() == 2) {
                    setJacNnzPattern(listNonZerosCts2, listNonZerosVars2);
                }
                setGradEvalCallback(new CallbackEvalG(jacobianMatrix, listNonZerosCts, listNonZerosVars, listNonZerosCts2, listNonZerosVars2, listNonLinearConsts, listVarChecker, lfNetwork, equationSystem));
            }
        }
    }

    private void setSolverParameters(KNSolver solver, KnitroSolverParameters knitroParameters) throws KNException {
        solver.setParam(KNConstants.KN_PARAM_GRADOPT, knitroParameters.getGradientComputationMode());
        DefaultKnitroSolverStoppingCriteria knitroSolverStoppingCriteria = (DefaultKnitroSolverStoppingCriteria) knitroParameters.getStoppingCriteria();
        solver.setParam(KNConstants.KN_PARAM_FEASTOL, knitroSolverStoppingCriteria.convEpsPerEq);
        solver.setParam(KNConstants.KN_PARAM_MAXIT, knitroParameters.getMaxIterations());
//        solver.setParam(KNConstants.KN_PARAM_DERIVCHECK, 1);
//        solver.setParam(KNConstants.KN_PARAM_DERIVCHECK_TOL, 0.0001);
//        solver.setParam(KNConstants.KN_PARAM_OUTLEV,4);
//        solver.setParam(KNConstants.KN_PARAM_OUTMODE,2);
//        solver.setParam(KNConstants.KN_PARAM_DEBUG ,1);

//        solver.setParam(KNConstants.KN_PARAM_MS_ENABLE, 0); // multi-start
//        solver.setParam(KNConstants.KN_PARAM_MS_NUMTHREADS, 1);
//        solver.setParam(KNConstants.KN_PARAM_CONCURRENT_EVALS, 0); //pas d'évaluations de callbacks concurrentes
//        solver.setParam(KNConstants.KN_PARAM_NUMTHREADS, 8);
//        solver.setParam(KNConstants.KN_PARAM_LINSOLVER_SCALING, ); // scaling for linear systems
    }

    @Override
    public AcSolverResult run(VoltageInitializer voltageInitializer, ReportNode reportNode) {
        AcSolverStatus status;
        int nbIter = -1;
        DoubleWrapper errorWrapper = new DoubleWrapper();
        errorWrapper.value = -1;
        double initialError = -1; //TODO

        AcSolverStatus acStatus = null;

        try {
            // Create instance of problem
            KnitroProblem instance = new KnitroProblem(network, equationSystem, targetVector, voltageInitializer, j, knitroParameters);
            KNSolver solver = new KNSolver(instance);
            solver.initProblem();

            // Set solver parameters
            setSolverParameters(solver, knitroParameters);

            // Solve
            solver.solve();
            KNSolution solution = solver.getSolution();
            List<Double> constraintValues = solver.getConstraintValues();
            acStatus = getAcStatusAndKnitroStatus(solution.getStatus());
            nbIter = solver.getNumberIters();
            errorWrapper.value = solver.getAbsFeasError();

            // Log solution
            LOGGER.info("Optimal objective value  = {}", solution.getObjValue());
            LOGGER.info("Feasibility violation    = {}", solver.getAbsFeasError());
            LOGGER.info("Optimality violation     = {}", solver.getAbsOptError());

            LOGGER.debug("Optimal x");
            for (int i = 0; i < solution.getX().size(); i++) {
                LOGGER.debug(" x[{}] = {}", i, solution.getX().get(i));
            }
            LOGGER.debug("Optimal constraint values (with corresponding multiplier)");
            for (int i = 0; i < instance.getNumCons(); i++) {
                LOGGER.debug(" c[{}] = {} (lambda = {} )", i, constraintValues.get(i), solution.getLambda().get(i));
            }
            LOGGER.debug("Constraint violation");
            for (int i = 0; i < instance.getNumCons(); i++) {
                LOGGER.debug(" violation[{}] = {} ", i, solver.getConViol(i));
            }

            // Load results in the network

            if (acStatus == AcSolverStatus.CONVERGED || knitroParameters.isAlwaysUpdateNetwork()) {
                equationSystem.getStateVector().set(toArray(solution.getX())); //update equations system
                AcSolverUtil.updateNetwork(network, equationSystem);
            }

//            // update network state variable //TODO later?
//            if (acStatus == AcSolverStatus.CONVERGED && knitroParameters.is(reportNode)) {
//                status = AcSolverStatus.UNREALISTIC_STATE;
//            }

        } catch (KNException e) {
            acStatus = AcSolverStatus.NO_CALCULATION;
            throw new PowsyblException("Exception found while trying to solve with Knitro");
        }

        double slackBusActivePowerMismatch = network.getSlackBuses().stream().mapToDouble(LfBus::getMismatchP).sum();
        return new AcSolverResult(acStatus, nbIter, slackBusActivePowerMismatch, errorWrapper, initialError);
    }
}