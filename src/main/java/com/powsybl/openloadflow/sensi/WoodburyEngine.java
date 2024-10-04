/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.openloadflow.dc.equations.AbstractClosedBranchDcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.ClosedBranchSide1DcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.PiModel;

import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gaël Macherel {@literal <gael.macherel@artelys.com>}
 */
public class WoodburyEngine {

    private final DcEquationSystemCreationParameters creationParameters;

    private final List<ComputedContingencyElement> contingencyElements;

    private final DenseMatrix contingenciesStates;

    private final List<ComputedActionElement> pstActionElements;

    private DenseMatrix pstActionsStates;

    public WoodburyEngine(DcEquationSystemCreationParameters creationParameters, List<ComputedContingencyElement> contingencyElements,
                          DenseMatrix contingenciesStates) {
        this.creationParameters = Objects.requireNonNull(creationParameters);
        this.contingencyElements = Objects.requireNonNull(contingencyElements);
        this.contingenciesStates = Objects.requireNonNull(contingenciesStates);
        this.pstActionElements = List.of();
    }

    public WoodburyEngine(DcEquationSystemCreationParameters creationParameters, List<ComputedContingencyElement> contingencyElements,
                          DenseMatrix contingenciesStates, List<ComputedActionElement> pstActionElements, DenseMatrix pstActionsStates) {
        this.creationParameters = Objects.requireNonNull(creationParameters);
        this.contingencyElements = Objects.requireNonNull(contingencyElements);
        this.contingenciesStates = Objects.requireNonNull(contingenciesStates);
        this.pstActionElements = Objects.requireNonNull(pstActionElements);
        this.pstActionsStates = Objects.requireNonNull(pstActionsStates);
    }

    private double calculatePower(LfBranch lfBranch) {
        PiModel piModel = lfBranch.getPiModel();
        return AbstractClosedBranchDcFlowEquationTerm.calculatePower(creationParameters.isUseTransformerRatio(), creationParameters.getDcApproximationType(), piModel);
    }

    private double calculatePower(LfBranch lfbranch, int tapPosition) {
        PiModel piModel = lfbranch.getPiModel().getModel(tapPosition);
        return AbstractClosedBranchDcFlowEquationTerm.calculatePower(creationParameters.isUseTransformerRatio(), creationParameters.getDcApproximationType(), piModel);
    }

    /**
     * Compute the flow transfer factors needed to calculate the post-contingency state values.
     */
    private void setAlphas(DenseMatrix states, int columnState) {
        if (contingencyElements.size() == 1 && pstActionElements.isEmpty()) {
            ComputedContingencyElement element = contingencyElements.iterator().next();
            LfBranch lfBranch = element.getLfBranch();
            ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();

            // we solve a*alpha = b
            double a = 1d / calculatePower(lfBranch) - (contingenciesStates.get(p1.getPh1Var().getRow(), element.getComputedElementIndex())
                    - contingenciesStates.get(p1.getPh2Var().getRow(), element.getComputedElementIndex()));
            double b = states.get(p1.getPh1Var().getRow(), columnState) - states.get(p1.getPh2Var().getRow(), columnState);
            element.setAlphaForWoodburyComputation(b / a);
        } else if (contingencyElements.isEmpty() && pstActionElements.size() == 1) {
            ComputedActionElement element = pstActionElements.iterator().next();
            LfBranch lfBranch = element.getLfBranch();
            ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();

            // we solve a*alpha = b
            int newTapPosition = element.getAction().getTapPositionChange().getNewTapPosition();
            double deltaX = 1d / (calculatePower(lfBranch) - calculatePower(lfBranch, newTapPosition));
            double a = deltaX - (pstActionsStates.get(p1.getPh1Var().getRow(), element.getComputedElementIndex())
                    - pstActionsStates.get(p1.getPh2Var().getRow(), element.getComputedElementIndex()));

            double newAlpha = lfBranch.getPiModel().getModel(newTapPosition).getA1();
            double b = states.get(p1.getPh1Var().getRow(), columnState) - states.get(p1.getPh2Var().getRow(), columnState) + newAlpha;
            element.setAlphaForWoodburyComputation(b / a);
        } else {
            // set local indexes of computed elements to use them in small matrix computation
            ComputedElement.setLocalIndexes(contingencyElements);
            ComputedElement.setLocalIndexes(pstActionElements);
            int size = contingencyElements.size() + pstActionElements.size();
            DenseMatrix rhs = new DenseMatrix(size, 1);
            DenseMatrix matrix = new DenseMatrix(size, size);

            for (ComputedContingencyElement contingencyElement : contingencyElements) {
                int i = contingencyElement.getLocalIndex();
                LfBranch lfBranch = contingencyElement.getLfBranch();
                ClosedBranchSide1DcFlowEquationTerm p1 = contingencyElement.getLfBranchEquation();
                rhs.set(i, 0, states.get(p1.getPh1Var().getRow(), columnState) - states.get(p1.getPh2Var().getRow(), columnState));

                // loop on contingencies to fill top-left quadrant of the matrix
                for (ComputedContingencyElement contingencyElement2 : contingencyElements) {
                    int j = contingencyElement2.getLocalIndex();
                    // if on the diagonal of the matrix, add variation of reactance
                    double deltaX = (i == j) ? 1d / calculatePower(lfBranch) : 0d;
                    double value = deltaX - (contingenciesStates.get(p1.getPh1Var().getRow(), contingencyElement2.getComputedElementIndex())
                            - contingenciesStates.get(p1.getPh2Var().getRow(), contingencyElement2.getComputedElementIndex()));
                    matrix.set(i, j, value);
                }

                // loop on actions to fill top-right quadrant of the matrix
                for (ComputedActionElement actionElement : pstActionElements) {
                    int j = contingencyElements.size() + actionElement.getLocalIndex();
                    double value = -(pstActionsStates.get(p1.getPh1Var().getRow(), actionElement.getComputedElementIndex())
                            - pstActionsStates.get(p1.getPh2Var().getRow(), actionElement.getComputedElementIndex()));
                    matrix.set(i, j, value);
                }
            }

            for (ComputedActionElement actionElement : pstActionElements) {
                int i = contingencyElements.size() + actionElement.getLocalIndex();
                LfBranch lfBranch = actionElement.getLfBranch();
                ClosedBranchSide1DcFlowEquationTerm p1 = actionElement.getLfBranchEquation();

                int newTapPosition = actionElement.getAction().getTapPositionChange().getNewTapPosition();
                double newAlpha = lfBranch.getPiModel().getModel(newTapPosition).getA1();
                rhs.set(i, 0, states.get(p1.getPh1Var().getRow(), columnState) - states.get(p1.getPh2Var().getRow(), columnState) + newAlpha);

                // loop on contingencies to fill bottom-left quadrant of the matrix
                for (ComputedContingencyElement contingencyElement : contingencyElements) {
                    int j = contingencyElement.getLocalIndex();
                    double value = -(contingenciesStates.get(p1.getPh1Var().getRow(), contingencyElement.getComputedElementIndex())
                            - contingenciesStates.get(p1.getPh2Var().getRow(), contingencyElement.getComputedElementIndex()));
                    matrix.set(i, j, value);
                }

                // loop on actions to fill bottom-right quadrant of the matrix
                for (ComputedActionElement actionElement2 : pstActionElements) {
                    int j = contingencyElements.size() + actionElement2.getLocalIndex();
                    // if on the diagonal of the matrix, add variation of reactance
                    double deltaX = (i == j) ? 1d / (calculatePower(lfBranch) - calculatePower(lfBranch, newTapPosition)) : 0d;
                    double value = deltaX - (pstActionsStates.get(p1.getPh1Var().getRow(), actionElement2.getComputedElementIndex())
                            - pstActionsStates.get(p1.getPh2Var().getRow(), actionElement2.getComputedElementIndex()));
                    matrix.set(i, j, value);
                }
            }
            try (LUDecomposition lu = matrix.decomposeLU()) {
                lu.solve(rhs); // rhs now contains state matrix
            }
            contingencyElements.forEach(element -> element.setAlphaForWoodburyComputation(rhs.get(element.getLocalIndex(), 0)));
            pstActionElements.forEach(element -> element.setAlphaForWoodburyComputation(rhs.get(contingencyElements.size() + element.getLocalIndex(), 0)));
        }
    }

    /**
     * Calculate post-contingency states values using pre-contingency states values and some flow transfer factors (alphas).
     * @return a matrix of post-contingency voltage angle states.
     */
    public DenseMatrix run(DenseMatrix preContingencyStates) {
        Objects.requireNonNull(preContingencyStates);
        // fill the post contingency matrices
        DenseMatrix postContingencyStates = new DenseMatrix(preContingencyStates.getRowCount(), preContingencyStates.getColumnCount());
        for (int columnIndex = 0; columnIndex < preContingencyStates.getColumnCount(); columnIndex++) {
            setAlphas(preContingencyStates, columnIndex);
            for (int rowIndex = 0; rowIndex < preContingencyStates.getRowCount(); rowIndex++) {
                double postContingencyValue = preContingencyStates.get(rowIndex, columnIndex);
                for (ComputedContingencyElement contingencyElement : contingencyElements) {
                    postContingencyValue += contingencyElement.getAlphaForWoodburyComputation()
                            * contingenciesStates.get(rowIndex, contingencyElement.getComputedElementIndex());
                }
                postContingencyStates.set(rowIndex, columnIndex, postContingencyValue);
            }
        }
        return postContingencyStates;
    }

    /**
     * Calculate post-contingency and post-actions states values, using pre-contingency states values and some flow transfer factors (alphas).
     * @return an array of post-contingency and post-actions voltage angle states.
     */
    public double[] run(double[] preContingencyStates) {
        Objects.requireNonNull(preContingencyStates);
        double[] postContingencyStates = new double[preContingencyStates.length];
        setAlphas(new DenseMatrix(preContingencyStates.length, 1, preContingencyStates), 0);
        for (int rowIndex = 0; rowIndex < preContingencyStates.length; rowIndex++) {
            double postContingencyValue = preContingencyStates[rowIndex];
            for (ComputedContingencyElement contingencyElement : contingencyElements) {
                postContingencyValue += contingencyElement.getAlphaForWoodburyComputation()
                        * contingenciesStates.get(rowIndex, contingencyElement.getComputedElementIndex());
            }
            for (ComputedActionElement actionElement : pstActionElements) {
                postContingencyValue += actionElement.getAlphaForWoodburyComputation()
                        * pstActionsStates.get(rowIndex, actionElement.getComputedElementIndex());
            }
            postContingencyStates[rowIndex] = postContingencyValue;
        }
        return postContingencyStates;
    }
}

