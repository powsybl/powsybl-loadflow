/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.commons.PowsyblException;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.equations.ClosedBranchSide1DcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

import java.util.Collection;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gaël Macherel {@literal <gael.macherel@artelys.com>}
 */
public abstract class AbstractComputedElement {
    private int computedElementIndex = -1; // index of the element in the rhs for +1-1
    private int localIndex = -1; // local index of the element : index of the element in the matrix used in the setAlphas method
    private double alphaForWoodburyComputation = Double.NaN;
    private final LfBranch lfBranch;
    private final ClosedBranchSide1DcFlowEquationTerm branchEquation;

    protected AbstractComputedElement(LfBranch lfBranch, ClosedBranchSide1DcFlowEquationTerm branchEquation) {
        this.lfBranch = lfBranch;
        this.branchEquation = branchEquation;
    }

    public int getComputedElementIndex() {
        return computedElementIndex;
    }

    public void setComputedElementIndex(final int index) {
        this.computedElementIndex = index;
    }

    public int getLocalIndex() {
        return localIndex;
    }

    protected void setLocalIndex(final int index) {
        this.localIndex = index;
    }

    public double getAlphaForWoodburyComputation() {
        return alphaForWoodburyComputation;
    }

    public void setAlphaForWoodburyComputation(double alphaForPostContingencyStates) {
        this.alphaForWoodburyComputation = alphaForPostContingencyStates;
    }

    public LfBranch getLfBranch() {
        return lfBranch;
    }

    public ClosedBranchSide1DcFlowEquationTerm getLfBranchEquation() {
        return branchEquation;
    }

    public static void setComputedElementIndexes(Collection<? extends AbstractComputedElement> elements) {
        int index = 0;
        for (AbstractComputedElement element : elements) {
            element.setComputedElementIndex(index++);
        }
    }

    public static void setLocalIndexes(Collection<? extends AbstractComputedElement> elements) {
        int index = 0;
        for (AbstractComputedElement element : elements) {
            element.setLocalIndex(index++);
        }
    }

    /**
     * Fills the right hand side with +1/-1 to model a branch contingency or action.
     */
    private static void fillRhs(EquationSystem<DcVariableType, DcEquationType> equationSystem, Collection<? extends AbstractComputedElement> computedElements, Matrix rhs) {
        for (AbstractComputedElement element : computedElements) {
            LfBranch lfBranch = element.getLfBranch();
            if (lfBranch.getBus1() == null || lfBranch.getBus2() == null) {
                continue;
            }
            LfBus bus1 = lfBranch.getBus1();
            LfBus bus2 = lfBranch.getBus2();
            if (bus1.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getComputedElementIndex(), -1);
            } else if (bus2.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getComputedElementIndex(), 1);
            } else {
                Equation<DcVariableType, DcEquationType> p1 = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                Equation<DcVariableType, DcEquationType> p2 = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p1.getColumn(), element.getComputedElementIndex(), 1);
                rhs.set(p2.getColumn(), element.getComputedElementIndex(), -1);
            }
        }
    }

    public static DenseMatrix initRhs(EquationSystem<DcVariableType, DcEquationType> equationSystem, Collection<? extends AbstractComputedElement> elements) {
        // otherwise, defining the rhs matrix will result in integer overflow
        int equationCount = equationSystem.getIndex().getSortedEquationsToSolve().size();
        int maxElements = Integer.MAX_VALUE / (equationCount * Double.BYTES);
        if (elements.size() > maxElements) {
            throw new PowsyblException("Too many elements " + elements.size()
                    + ", maximum is " + maxElements + " for a system with " + equationCount + " equations");
        }

        DenseMatrix rhs = new DenseMatrix(equationCount, elements.size());
        fillRhs(equationSystem, elements, rhs);
        return rhs;
    }

    public static DenseMatrix calculateElementsStates(DcLoadFlowContext loadFlowContext, Collection<? extends AbstractComputedElement> computedElements) {
        DenseMatrix elementsStates = initRhs(loadFlowContext.getEquationSystem(), computedElements); // rhs with +1 -1 on computed elements
        loadFlowContext.getJacobianMatrix().solveTransposed(elementsStates);
        return elementsStates;
    }

    public abstract void applyToConnectivity(GraphConnectivity<LfBus, LfBranch> connectivity);
}
