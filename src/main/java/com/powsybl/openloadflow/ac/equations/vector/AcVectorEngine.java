/*
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.openloadflow.ac.equations.vector;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.openloadflow.ac.equations.AbstractClosedBranchAcFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.AbstractLfNetworkListener;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.DoubleSupplier;
import java.util.stream.IntStream;

/**
 * @author Didier Vidal {@literal <didier.vidal_externe at rte-france.com>}
 *
 * A data container that contains primitive type arrays that can be iterrated
 * efficiently to avoid memory cache misses
 * foccusses on P and Q derived by V and Phi. Other combinations are not vectorized.
 */
public class AcVectorEngine implements StateVectorListener, EquationSystemListener, VectorEngine<AcVariableType> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcVectorEngine.class);

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    public final boolean[] networkDataInitialized;
    public final double[] b1;
    public final double[] b2;
    public final double[] g1;
    public final double[] g2;
    public final double[] y;
    public final double[] ksi;
    public final double[] g12;
    public final double[] b12;

    // possibly computed input values
    private final ArrayList<AbstractClosedBranchAcFlowEquationTerm> supplyingTerms = new ArrayList<>();
    public final double[] a1;
    public final double[] r1;

    // variables
    public final Variable<AcVariableType>[] v1Var;
    private final double[] v1;
    public final Variable<AcVariableType>[] v2Var;
    private final double[] v2;
    public final Variable<AcVariableType>[] ph1Var;
    private final double[] ph1;
    public final Variable<AcVariableType>[] ph2Var;
    private final double[] ph2;

    // indexes to compute derivatives
    private boolean equationDataValid;
    private boolean equationOrderValid;
    private int[] sortedEquationIndexArray;
    private int[] variableCountPerEquation;
    private int[] variablePerEquationIndex;
    private Variable<AcVariableType>[] variablesPerEquation;
    private double[] deriveResultPerVariableAndEquation;
    private int[] matrixIndexPerVariableAndEquation;
    private EquationTerm<AcVariableType, AcEquationType>[] termsByVariableAndEquation;

    // term data ordered by branch
    record TermData(EquationTerm<AcVariableType, AcEquationType> term,
                    boolean isBranch,
                    int branchNum,
                    int busNum,
                    int indexForResult,
                    Variable<AcVariableType> v) {
    }

    // equation replicated data for quick access
    private boolean[] equationActiveStatus;
    private int[] equationColumn;
    private double[] evalResultPerEquation;

    // term replicated data for quick access
    private boolean[] termActiveStatus;
    private int[] termBranchNum;
    public DoubleSupplier[] a1TermSupplier;
    public DoubleSupplier[] r1TermSupplier;

    // sorted term data per equation (for eval)
    private EquationTerm<AcVariableType, AcEquationType>[] sortedTermsForEval;
    private int[] termActiveStatusIndexForEval;
    private int[] termEquationActiveStatusIndexForEval;
    private int[] termByEvalResultIndex;
    private int[] termByEquationBranchNumForEval;
    private VecToVal[] sortedTermsVecToValForEval;

    // sorted term data per equation and variable (for der)
    private EquationTerm<AcVariableType, AcEquationType>[] sortedTermsForDer;
    private int[] termActiveStatusIndexForDer;
    private int[] termEquationActiveStatusIndexForDer;
    private Variable<AcVariableType>[] termVariable;
    private int[] termByVariableDeriveResultIndex;
    private int[] termByVariableBranchNumForDer;
    private VecToVal[] sortedTermsVecToValForDer;

    public AcVectorEngine(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        this.equationSystem = equationSystem;
        if (equationSystem != null) {
            equationSystem.setVectorEngine(this);
        }

        if (network != null) {
            network.addListener(new AbstractLfNetworkListener() {
                @Override
                public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
                    a1[branch.getNum()] = branch.getPiModel().getA1();
                    r1[branch.getNum()] = branch.getPiModel().getR1();
                }
            });
        }

        int branchCount = network == null ? 1 : network.getBranches().size();

        b1 = new double[branchCount];
        b2 = new double[branchCount];
        g1 = new double[branchCount];
        g2 = new double[branchCount];
        y = new double[branchCount];
        ksi = new double[branchCount];
        g12 = new double[branchCount];
        b12 = new double[branchCount];
        networkDataInitialized = new boolean[branchCount];

        a1 = new double[branchCount];

        r1 = new double[branchCount];

        v1Var = new Variable[branchCount];
        v1 = new double[branchCount];
        v2Var = new Variable[branchCount];
        v2 = new double[branchCount];
        ph1Var = new Variable[branchCount];
        ph1 = new double[branchCount];
        ph2Var = new Variable[branchCount];
        ph2 = new double[branchCount];

        if (equationSystem != null) {
            equationSystem.getStateVector().addListener(this);
            equationSystem.addListener(this);
        }
    }

    @Override
    public void onStateUpdate() {
        updateVariables();
    }

    @Override
    public void onEquationChange(Equation equation, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_CREATED:
            case EQUATION_REMOVED:
                equationDataValid = false;
                break;
            case EQUATION_ACTIVATED:
            case EQUATION_DEACTIVATED:
                if (equation.getVectorIndex() >= 0) {
                    equationActiveStatus[equation.getVectorIndex()] = equation.isActive();
                }
            case EQUATION_COLUMN_CHANGED:
                if (equation.getVectorIndex() >= 0) {
                    equationColumn[equation.getVectorIndex()] = equation.getColumn();
                }
                equationOrderValid = false;
                break;
        }
    }

    @Override
    public void onEquationTermChange(EquationTerm term, EquationTermEventType eventType) {
        if (eventType == EquationTermEventType.EQUATION_TERM_ACTIVATED || eventType == EquationTermEventType.EQUATION_TERM_DEACTIVATED) {
            if (term.getVectorIndex() >= 0) {
                termActiveStatus[term.getVectorIndex()] = term.isActive();
            }
        }
    }

    @Override
    public void evalLhs(double[] array) {
        if (!equationDataValid) {
            initEquationData();
        }

        evalSortedTerms();

        for (int eqIndex = 0; eqIndex < equationActiveStatus.length; eqIndex++) {
            if (equationActiveStatus[eqIndex]) {
                int col = equationColumn[eqIndex];
                array[col] = evalResultPerEquation[eqIndex];
            }
        }
    }

    public void addSupplyingTerm(AbstractClosedBranchAcFlowEquationTerm t) {
        supplyingTerms.add(t);
        equationDataValid = false;
    }

    private void updateVariables() {
        StateVector stateVector = equationSystem.getStateVector();
        for (int i = 0; i < v1Var.length; i++) {
            v1[i] = v1Var[i] != null && v1Var[i].getRow() >= 0 ? stateVector.get(v1Var[i].getRow()) : Double.NaN;
            v2[i] = v2Var[i] != null && v2Var[i].getRow() >= 0 ? stateVector.get(v2Var[i].getRow()) : Double.NaN;
            ph1[i] = ph1Var[i] != null && ph1Var[i].getRow() >= 0 ? stateVector.get(ph1Var[i].getRow()) : Double.NaN;
            ph2[i] = ph2Var[i] != null && ph2Var[i].getRow() >= 0 ? stateVector.get(ph2Var[i].getRow()) : Double.NaN;
        }
    }

    private void initEquationData() {
        // reset all term vector index
        if (termsByVariableAndEquation != null) {
            Arrays.stream(termsByVariableAndEquation).forEach(t -> t.setVectorIndex(-1));
        }

        Collection<Equation<AcVariableType, AcEquationType>> equationList = equationSystem.getEquations()
                .stream().sorted(Comparator.comparingInt(Equation::getElementNum)).toList();
        int equationCount = equationList.size();
        sortedEquationIndexArray = new int[equationCount];
        variableCountPerEquation = new int[equationCount];
        equationActiveStatus = new boolean[equationCount];
        equationColumn = new int[equationCount];
        evalResultPerEquation = new double[equationCount];
        int index = 0;
        int variableIndexSize = 0;
        int termCount = 0;
        for (Equation<AcVariableType, AcEquationType> e : equationList) {
            e.setVectorIndex(index);
            equationActiveStatus[index] = e.isActive();
            equationColumn[index] = e.getColumn();
            int equationVariableCount = e.getVariableCount();
            variableIndexSize += equationVariableCount;
            variableCountPerEquation[index] = equationVariableCount;
            termCount += e.getTerms().size();
            index += 1;
        }
        termActiveStatus = new boolean[termCount];
        termBranchNum = new int[termCount];
        a1TermSupplier = new DoubleSupplier[termCount];
        r1TermSupplier = new DoubleSupplier[termCount];
        variablePerEquationIndex = new int[equationCount];
        variablesPerEquation = new Variable[variableIndexSize];
        matrixIndexPerVariableAndEquation = new int[variableIndexSize];
        deriveResultPerVariableAndEquation = new double[variableIndexSize];
        List<EquationTerm<AcVariableType, AcEquationType>> termsByVariableAndEquationList = new ArrayList<>();
        List<TermData> termDataListForDer = new ArrayList<>();
        List<TermData> termDataListForEval = new ArrayList<>();
        int indexVar = 0;
        int indexEq = 0;
        int indexForTermStatus = 0;
        for (Equation<AcVariableType, AcEquationType> e : equationList) {
            for (EquationTerm<AcVariableType, AcEquationType> t : e.getTerms()) {
                t.setVectorIndex(indexForTermStatus);
                termActiveStatus[indexForTermStatus] = t.isActive();
                if (t instanceof AbstractClosedBranchAcFlowEquationTerm brTerm) {
                    termBranchNum[indexForTermStatus] = brTerm.getElementNum();
                    a1TermSupplier[indexForTermStatus] = brTerm.getA1Supplier();
                    r1TermSupplier[indexForTermStatus] = brTerm.getR1Supplier();
                    r1[brTerm.getElementNum()] = brTerm.r1();
                    a1[brTerm.getElementNum()] = brTerm.a1();
                }
                termDataListForEval.add(new TermData(t,
                        t.getElementType() == ElementType.BRANCH,
                        t.getElementNum(),
                        t.getEquation().getElementNum(),
                        indexEq,
                        null));
                indexForTermStatus += 1;
            }
            variablePerEquationIndex[indexEq] = indexVar;
            for (Variable<AcVariableType> v : e.getVariables()) {
                variablesPerEquation[indexVar] = v;
                for (EquationTerm<AcVariableType, AcEquationType> t : e.getTerms(v)) {
                    termsByVariableAndEquationList.add(t);
                    termDataListForDer.add(new TermData(t,
                            t.getElementType() == ElementType.BRANCH,
                            t.getElementNum(),
                            e.getElementNum(),
                            indexVar,
                            v));
                }
                indexVar += 1;
            }
            indexEq += 1;
        }
        termsByVariableAndEquation = termsByVariableAndEquationList.toArray(new EquationTerm[0]);
        for (int eqIndex = 0; eqIndex < equationActiveStatus.length; eqIndex++) {
            if (equationActiveStatus[eqIndex]) {
                int varEnd = variablePerEquationIndex[eqIndex] + variableCountPerEquation[eqIndex];
                for (int varIndex = variablePerEquationIndex[eqIndex]; varIndex < varEnd; varIndex++) {
                    Variable<AcVariableType> v = variablesPerEquation[varIndex];
                }
            }
        }
        termDataListForDer.sort((t1, t2) -> {
            // Branch elements together, sorted by branchNum then by bus, then by variable
            int compare = -Boolean.compare(t1.isBranch, t2.isBranch); // Put branch terms first
            if (compare != 0) {
                return compare;
            }

            compare = t1.branchNum - t2.branchNum;
            if (compare != 0) {
                return compare;
            }

            compare = t1.busNum - t2.busNum;
            if (compare != 0) {
                return compare;
            }

            return t1.v.compareTo(t2.v);

        });

        int termByVariableCount = termDataListForDer.size();
        sortedTermsForDer = new EquationTerm[termByVariableCount];
        termActiveStatusIndexForDer = new int[termByVariableCount];
        termEquationActiveStatusIndexForDer = new int[termByVariableCount];
        termVariable = new Variable[termByVariableCount];
        termByVariableDeriveResultIndex = new int[termByVariableCount];
        termByVariableBranchNumForDer = new int[termByVariableCount];
        sortedTermsVecToValForDer = new VecToVal[termByVariableCount];
        int sortedTermIndex = 0;
        for (TermData termData : termDataListForDer) {
            sortedTermsForDer[sortedTermIndex] = termData.term;
            termActiveStatusIndexForDer[sortedTermIndex] = termData.term.getVectorIndex();
            termEquationActiveStatusIndexForDer[sortedTermIndex] = termData.term.getEquation().getVectorIndex();
            termVariable[sortedTermIndex] = termData.v;
            termByVariableBranchNumForDer[sortedTermIndex] = termData.isBranch ? termData.branchNum : -1;
            sortedTermsVecToValForDer[sortedTermIndex] = termData.term.getVecToVal(termData.v);
            termByVariableDeriveResultIndex[sortedTermIndex] = termData.indexForResult;
            sortedTermIndex += 1;
        }

        termDataListForEval.sort((t1, t2) -> {
            // Branch elements together, sorted by branchNum then by bus, then by variable
            int compare = -Boolean.compare(t1.isBranch, t2.isBranch); // Put branch terms first
            if (compare != 0) {
                return compare;
            }

            compare = t1.branchNum - t2.branchNum;
            if (compare != 0) {
                return compare;
            }

            return t1.busNum - t2.busNum;

        });

        int termByEquationCount = termDataListForEval.size();
        sortedTermsForEval = new EquationTerm[termByEquationCount];
        termActiveStatusIndexForEval = new int[termByEquationCount];
        termEquationActiveStatusIndexForEval = new int[termByEquationCount];
        termByEvalResultIndex = new int[termByEquationCount];
        termByEquationBranchNumForEval = new int[termByEquationCount];
        sortedTermsVecToValForEval = new VecToVal[termByEquationCount];
        sortedTermIndex = 0;
        for (TermData termData : termDataListForEval) {
            sortedTermsForEval[sortedTermIndex] = termData.term;
            termActiveStatusIndexForEval[sortedTermIndex] = termData.term.getVectorIndex();
            termEquationActiveStatusIndexForEval[sortedTermIndex] = termData.term.getEquation().getVectorIndex();
            termByEquationBranchNumForEval[sortedTermIndex] = termData.term instanceof AbstractClosedBranchAcFlowEquationTerm ? termData.branchNum : -1;
            sortedTermsVecToValForEval[sortedTermIndex] = termData.term.getVecToVal(null);
            termByEvalResultIndex[sortedTermIndex] = termData.indexForResult;
            sortedTermIndex += 1;
        }

        equationDataValid = true;
    }

    private void sortEquations() {
        Iterator<Integer> it = IntStream.range(0, equationColumn.length).boxed()
                .sorted((i1, i2) -> equationColumn[i1] - equationColumn[i2]).iterator();
        int i = 0;
        while (it.hasNext()) {
            sortedEquationIndexArray[i] = it.next();
            i += 1;
        }
        equationOrderValid = true;
    }

    @Override
    public void der(boolean update, Matrix matrix) {

        if (!equationDataValid) {
            initEquationData();
            equationOrderValid = false;
        }

        if (!equationOrderValid) {
            sortEquations();
        }

        updateVariables(); // do not depend on listener call order

        derSortedTerms();

        for (int sortedEqIndex = 0; sortedEqIndex < equationActiveStatus.length; sortedEqIndex++) {
            int eqIndex = sortedEquationIndexArray[sortedEqIndex];
            if (equationActiveStatus[eqIndex]) {
                int col = equationColumn[eqIndex];
                int varEnd = variablePerEquationIndex[eqIndex] + variableCountPerEquation[eqIndex];
                for (int varIndex = variablePerEquationIndex[eqIndex]; varIndex < varEnd; varIndex++) {
                    Variable<AcVariableType> v = variablesPerEquation[varIndex];
                    int row = v.getRow();
                    if (row >= 0) {
                        if (update) {
                            matrix.addAtIndex(matrixIndexPerVariableAndEquation[varIndex], deriveResultPerVariableAndEquation[varIndex]);
                        } else {
                            matrixIndexPerVariableAndEquation[varIndex] = matrix.addAndGetIndex(row, col, deriveResultPerVariableAndEquation[varIndex]);
                        }
                    }
                }
            }
        }
    }

    private void evalSortedTerms() {
        Arrays.fill(evalResultPerEquation, 0);
        evalSortedTermsVec();
        evalSortedTermsObj();
    }

    private void evalSortedTermsVec() {
        int branchNum = -1;
        double a1Evaluated = Double.NaN;
        double r1Evaluated = Double.NaN;
        double sinKsi = Double.NaN;
        double cosKsi = Double.NaN;
        double theta2 = Double.NaN;
        double sinTheta2 = Double.NaN;
        double cosTheta2 = Double.NaN;
        double theta1 = Double.NaN;
        double sinTheta1 = Double.NaN;
        double cosTheta1 = Double.NaN;
        for (int termIndex = 0; termIndex < sortedTermsForEval.length; termIndex++) {
            if (equationActiveStatus[termEquationActiveStatusIndexForEval[termIndex]] &&
                    termActiveStatus[termActiveStatusIndexForEval[termIndex]]) {
                int termStatusIndex = termActiveStatusIndexForEval[termIndex];
                if (termByEquationBranchNumForEval[termIndex] != branchNum && termByEquationBranchNumForEval[termIndex] != -1) {
                    branchNum = termByEquationBranchNumForEval[termIndex];
                    sinKsi = FastMath.sin(ksi[branchNum]);
                    cosKsi = FastMath.cos(ksi[branchNum]);
                    a1Evaluated = a1TermSupplier[termStatusIndex] == null ? a1[branchNum] : a1TermSupplier[termStatusIndex].getAsDouble();
                    r1Evaluated = r1TermSupplier[termStatusIndex] == null ? r1[branchNum] : r1TermSupplier[termStatusIndex].getAsDouble();
                    theta2 = AbstractClosedBranchAcFlowEquationTerm.theta2(ksi[branchNum], ph1[branchNum], a1Evaluated, ph2[branchNum]);
                    sinTheta2 = FastMath.sin(theta2);
                    cosTheta2 = FastMath.cos(theta2);
                    theta1 = AbstractClosedBranchAcFlowEquationTerm.theta1(ksi[branchNum], ph1[branchNum], a1Evaluated, ph2[branchNum]);
                    sinTheta1 = FastMath.sin(theta1);
                    cosTheta1 = FastMath.cos(theta1);
                }
                if (sortedTermsVecToValForEval[termIndex] != null) {
                    evalResultPerEquation[termByEvalResultIndex[termIndex]] +=
                        sortedTermsVecToValForEval[termIndex].value(v1[branchNum], v2[branchNum], sinKsi, cosKsi, sinTheta2, cosTheta2, sinTheta1, cosTheta1,
                                b1[branchNum], b2[branchNum],
                                g1[branchNum], g2[branchNum], y[branchNum], g12[branchNum], b12[branchNum], a1Evaluated, r1Evaluated);
                }
            }
        }
    }

    private void evalSortedTermsObj() {
        for (int termIndex = 0; termIndex < sortedTermsForEval.length; termIndex++) {
            if (equationActiveStatus[termEquationActiveStatusIndexForEval[termIndex]] &&
                    termActiveStatus[termActiveStatusIndexForEval[termIndex]]) {
                if (sortedTermsVecToValForEval[termIndex] == null) {
                    evalResultPerEquation[termByEvalResultIndex[termIndex]] += sortedTermsForEval[termIndex].evalLhs();
                }

            }
        }
    }

    private void derSortedTerms() {
        Arrays.fill(deriveResultPerVariableAndEquation, 0);
        derSortedTermsVec();
        derSortedTermsObj();
    }

    private void derSortedTermsVec() {
        int branchNum = -1;
        double a1Evaluated = Double.NaN;
        double r1Evaluated = Double.NaN;
        double sinKsi = Double.NaN;
        double cosKsi = Double.NaN;
        double theta2 = Double.NaN;
        double sinTheta2 = Double.NaN;
        double cosTheta2 = Double.NaN;
        double theta1 = Double.NaN;
        double sinTheta1 = Double.NaN;
        double cosTheta1 = Double.NaN;
        for (int termIndex = 0; termIndex < sortedTermsForDer.length; termIndex++) {
            if (equationActiveStatus[termEquationActiveStatusIndexForDer[termIndex]] &&
                    termActiveStatus[termActiveStatusIndexForDer[termIndex]]) {
                int termStatusIndex = termActiveStatusIndexForDer[termIndex];
                if (termByVariableBranchNumForDer[termIndex] != branchNum && termByVariableBranchNumForDer[termIndex] != -1) {
                    branchNum = termByVariableBranchNumForDer[termIndex];
                    sinKsi = FastMath.sin(ksi[branchNum]);
                    cosKsi = FastMath.cos(ksi[branchNum]);
                    a1Evaluated = a1TermSupplier[termStatusIndex] == null ? a1[branchNum] : a1TermSupplier[termStatusIndex].getAsDouble();
                    r1Evaluated = r1TermSupplier[termStatusIndex] == null ? r1[branchNum] : r1TermSupplier[termStatusIndex].getAsDouble();
                    theta2 = AbstractClosedBranchAcFlowEquationTerm.theta2(ksi[branchNum], ph1[branchNum], a1Evaluated, ph2[branchNum]);
                    sinTheta2 = FastMath.sin(theta2);
                    cosTheta2 = FastMath.cos(theta2);
                    theta1 = AbstractClosedBranchAcFlowEquationTerm.theta1(ksi[branchNum], ph1[branchNum], a1Evaluated, ph2[branchNum]);
                    sinTheta1 = FastMath.sin(theta1);
                    cosTheta1 = FastMath.cos(theta1);
                    // TODO: incrementalPhaseShifterActovePowerControlTest fails if those terms are shared per branch
                    // Add a listener on tap position change
                }
                if (sortedTermsVecToValForDer[termIndex] != null) {
                    deriveResultPerVariableAndEquation[termByVariableDeriveResultIndex[termIndex]] +=
                            sortedTermsVecToValForDer[termIndex].value(v1[branchNum], v2[branchNum], sinKsi, cosKsi, sinTheta2, cosTheta2, sinTheta1, cosTheta1,
                                    b1[branchNum], b2[branchNum],
                                    g1[branchNum], g2[branchNum], y[branchNum], g12[branchNum], b12[branchNum], a1Evaluated, r1Evaluated);
                }
            }
        }
    }

    private void derSortedTermsObj() {
        for (int termIndex = 0; termIndex < sortedTermsForDer.length; termIndex++) {
            if (equationActiveStatus[termEquationActiveStatusIndexForDer[termIndex]] &&
                    termActiveStatus[termActiveStatusIndexForDer[termIndex]]) {
                if (sortedTermsVecToValForDer[termIndex] == null) {
                    deriveResultPerVariableAndEquation[termByVariableDeriveResultIndex[termIndex]] += sortedTermsForDer[termIndex].der(termVariable[termIndex]);
                }

            }
        }
    }

}
