/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.commons.util.trove.TIntArrayListHack;
import gnu.trove.list.array.TIntArrayList;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class EquationArray<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    private final E type;

    private final int elementCount;

    private final EquationSystem<V, E> equationSystem;

    private final boolean[] elementActive;

    private int firstColumn = -1;

    private int[] elementNumToColumn;

    private int length;

    private final List<EquationTermArray<V, E>> termArrays = new ArrayList<>();

    private List<EquationDerivativeVector<V>> equationDerivativeVectors;

    static class MatrixElementIndexes {
        private final TIntArrayList indexes = new TIntArrayList();

        private int get(int i) {
            if (i >= indexes.size()) {
                indexes.add(-1);
            }
            return indexes.get(i);
        }

        private void set(int i, int index) {
            indexes.set(i, index);
        }

        void reset() {
            indexes.clear();
        }
    }

    private final MatrixElementIndexes matrixElementIndexes = new MatrixElementIndexes();

    static class EquationDerivativeElement<V extends Enum<V> & Quantity> {
        int termArrayNum;
        int termNum;
        Variable<V> derVariable;
        int derLocalIndex;

        EquationDerivativeElement(int termArrayNum, int termNum, Variable<V> derVariable, int derLocalIndex) {
            this.termArrayNum = termArrayNum;
            this.termNum = termNum;
            this.derVariable = derVariable;
            this.derLocalIndex = derLocalIndex;
        }
    }

    static class EquationDerivativeVector<V extends Enum<V> & Quantity> {
        private final List<EquationDerivativeElement<V>> elements = new ArrayList<>();

        private final TIntArrayListHack termArrayNums = new TIntArrayListHack();
        private final TIntArrayListHack termNums = new TIntArrayListHack();
        private final List<Variable<V>> derVariables = new ArrayList<>();
        private final TIntArrayListHack derLocalIndexes = new TIntArrayListHack();

        void addTerm(int termNum, int termArrayNum, Variable<V> derVariable, int derLocalIndex) {
            elements.add(new EquationDerivativeElement<>(termArrayNum, termNum, derVariable, derLocalIndex));
        }

        void sortByVariableRowAndVectorizedLocalNum() {
            elements.sort(Comparator.comparingInt(o -> o.derVariable.getRow()));
            for (EquationDerivativeElement<V> element : elements) {
                termArrayNums.add(element.termArrayNum);
                termNums.add(element.termNum);
                derVariables.add(element.derVariable);
                derLocalIndexes.add(element.derLocalIndex);
            }
        }
    }

    public EquationArray(E type, int elementCount, EquationSystem<V, E> equationSystem) {
        this.type = Objects.requireNonNull(type);
        this.elementCount = elementCount;
        this.equationSystem = Objects.requireNonNull(equationSystem);
        elementActive = new boolean[elementCount];
        Arrays.fill(elementActive, true);
        this.length = elementCount; // all activated initially
    }

    public E getType() {
        return type;
    }

    public int getElementCount() {
        return elementCount;
    }

    public int[] getElementNumToColumn() {
        if (elementNumToColumn == null) {
            elementNumToColumn = new int[elementCount];
            int column = firstColumn;
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                if (elementActive[elementNum]) {
                    elementNumToColumn[elementNum] = column++;
                }
            }
        }
        return elementNumToColumn;
    }

    public int getElementNumToColumn(int elementNum) {
        return getElementNumToColumn()[elementNum];
    }

    private void invalidateElementNumToColumn() {
        elementNumToColumn = null;
        matrixElementIndexes.reset();
    }

    public EquationSystem<V, E> getEquationSystem() {
        return equationSystem;
    }

    public int getFirstColumn() {
        return firstColumn;
    }

    public void setFirstColumn(int firstColumn) {
        this.firstColumn = firstColumn;
        invalidateElementNumToColumn();
    }

    public int getLength() {
        return length;
    }

    public boolean isElementActive(int elementNum) {
        return elementActive[elementNum];
    }

    public void setElementActive(int elementNum, boolean active) {
        if (active != this.elementActive[elementNum]) {
            this.elementActive[elementNum] = active;
            if (active) {
                length++;
            } else {
                length--;
            }
            invalidateElementNumToColumn();
            equationSystem.notifyEquationArrayChange(this, elementNum,
                    active ? EquationEventType.EQUATION_ACTIVATED : EquationEventType.EQUATION_DEACTIVATED);
        }
    }

    public List<EquationTermArray<V, E>> getTermArrays() {
        return termArrays;
    }

    public void addTermArray(EquationTermArray<V, E> termArray) {
        Objects.requireNonNull(termArray);
        termArray.setEquationArray(this);
        termArrays.add(termArray);
        invalidateEquationDerivativeVectors();
    }

    public BaseEquation<V, E> getElement(int elementNum) {
        return new BaseEquation<>() {
            @Override
            public boolean isActive() {
                return isElementActive(elementNum);
            }

            @Override
            public void setActive(boolean active) {
                setElementActive(elementNum, active);
            }

            @Override
            public int getColumn() {
                return getElementNumToColumn(elementNum);
            }

            @Override
            public BaseEquation<V, E> addTerm(BaseEquationTerm<V, E> term) {
                var termImpl = (EquationTermArray.EquationTermArrayElementImpl<V, E>) term;
                termImpl.equationTermArray.addTerm(elementNum, termImpl.termElementNum);
                return this;
            }

            @Override
            public <T extends BaseEquationTerm<V, E>> BaseEquation<V, E> addTerms(List<T> terms) {
                for (T term : terms) {
                    addTerm(term);
                }
                return this;
            }

            @Override
            public <T extends BaseEquationTerm<V, E>> List<T> getTerms() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double eval() {
                double value = 0;
                for (EquationTermArray<V, E> termArray : termArrays) {
                    var termNums = termArray.getTermNumsForEquationElementNum(elementNum);
                    for (int i = 0; i < termNums.size(); i++) {
                        int termNum = termNums.get(i);
                        // skip inactive terms
                        if (termArray.isTermActive(termNum)) {
                            int termElementNum = termArray.getTermElementNum(termNum);
                            value += termArray.eval(termElementNum);
                        }
                    }
                }
                return value;
            }
        };
    }

    public void eval(double[] values) {
        Arrays.fill(values, firstColumn, firstColumn + length, 0);
        for (EquationTermArray<V, E> termArray : termArrays) {
            double[] termValues = termArray.eval();
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                // skip inactive equations
                if (!elementActive[elementNum]) {
                    continue;
                }
                var termNums = termArray.getTermNumsForEquationElementNum(elementNum);
                for (int i = 0; i < termNums.size(); i++) {
                    int termNum = termNums.get(i);
                    // skip inactive terms
                    if (termArray.isTermActive(termNum)) {
                        int termElementNum = termArray.getTermElementNum(termNum);
                        values[getElementNumToColumn(elementNum)] += termValues[termElementNum];
                    }
                }
            }
        }
    }

    public interface DerHandler<V extends Enum<V> & Quantity> {

        int onDer(int column, int row, double value, int matrixElementIndex);
    }

    private void updateEquationDerivativeVectors() {
        if (equationDerivativeVectors == null) {
            equationDerivativeVectors = new ArrayList<>();
            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                equationDerivativeVectors.add(null);
            }

            for (int elementNum = 0; elementNum < elementCount; elementNum++) {
                var equationDerivativeVector = equationDerivativeVectors.get(elementNum);
                if (equationDerivativeVector == null) {
                    equationDerivativeVector = new EquationDerivativeVector<>();
                    equationDerivativeVectors.set(elementNum, equationDerivativeVector);
                }
                // vectorize terms to evaluate
                for (int termArrayNum = 0; termArrayNum < termArrays.size(); termArrayNum++) {
                    EquationTermArray<V, E> termArray = termArrays.get(termArrayNum);
                    var termNums = termArray.getTermNumsForEquationElementNum(elementNum);
                    for (int i = 0; i < termNums.size(); i++) {
                        int termNum = termNums.getQuick(i);
                        // for each term of each, add an entry for each derivative operation we need
                        var termDerivatives = termArray.getTermDerivatives(termNum);
                        for (Derivative<V> derivative : termDerivatives) {
                            Variable<V> derVariable = derivative.getVariable();
                            int derLocalIndex = derivative.getLocalIndex();
                            equationDerivativeVector.addTerm(termNum, termArrayNum, derVariable, derLocalIndex);
                        }
                    }
                }
            }

            for (EquationDerivativeVector<V> equationDerivativeVector : equationDerivativeVectors) {
                equationDerivativeVector.sortByVariableRowAndVectorizedLocalNum();
            }
        }
    }

    void invalidateEquationDerivativeVectors() {
        equationDerivativeVectors = null;
        matrixElementIndexes.reset();
    }

    public void der(DerHandler<V> handler) {
        Objects.requireNonNull(handler);

        updateEquationDerivativeVectors();

        // compute all derivatives for each of the term array
        List<double[][]> termDerValuesByArrayIndex = new ArrayList<>(termArrays.size());
        for (EquationTermArray<V, E> termArray : termArrays) {
            termDerValuesByArrayIndex.add(termArray.evalDer());
        }

        // calculate all derivative values
        // process column by column so equation by equation of the array
        int valueIndex = 0;
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            // skip inactive elements
            if (!elementActive[elementNum]) {
                continue;
            }

            int column = getElementNumToColumn(elementNum);
            // for each equation of the array we already have the list of terms to derive and its variable sorted
            // by variable row (required by solvers)
            EquationDerivativeVector<V> equationDerivativeVector = equationDerivativeVectors.get(elementNum);

            // process term by term
            double value = 0;
            int prevRow = -1;
            boolean valueUpdated = false;
            for (int i = 0; i < equationDerivativeVector.termNums.size(); i++) {
                // get term array to which this term belongs
                int termArrayNum = equationDerivativeVector.termArrayNums.getQuick(i);
                EquationTermArray<V, E> termArray = termArrays.get(termArrayNum);

                // the derivative variable row
                int row = equationDerivativeVector.derVariables.get(i).getRow();

                // if an element at (row, column) is complete (we switch to another row), notify
                if (valueUpdated && row != prevRow) {
                    onDer(handler, column, prevRow, value, valueIndex);
                    valueIndex++;
                    value = 0;
                    valueUpdated = false;
                }
                prevRow = row;

                int termNum = equationDerivativeVector.termNums.getQuick(i);
                // skip inactive terms and get term derivative value
                if (termArray.isTermActive(termNum)) {
                    // get derivative local index
                    int derLocalIndex = equationDerivativeVector.derLocalIndexes.getQuick(i);

                    // add value (!!! we can have multiple terms contributing to same matrix element)
                    double[][] termDerValues = termDerValuesByArrayIndex.get(termArrayNum);
                    int termElementNum = termArray.getTermElementNum(termNum);
                    value += termDerValues[derLocalIndex][termElementNum];
                    valueUpdated = true;
                }
            }

            // remaining notif
            if (valueUpdated) {
                onDer(handler, column, prevRow, value, valueIndex);
                valueIndex++;
            }
        }
    }

    private void onDer(DerHandler<V> handler, int column, int row, double value, int valueIndex) {
        int matrixElementIndex = handler.onDer(column, row, value, matrixElementIndexes.get(valueIndex));
        matrixElementIndexes.set(valueIndex, matrixElementIndex);
    }

    public void write(Writer writer, boolean writeInactiveEquations) throws IOException {
        for (int elementNum = 0; elementNum < elementCount; elementNum++) {
            if (writeInactiveEquations || isElementActive(elementNum)) {
                if (!isElementActive(elementNum)) {
                    writer.write("[ ");
                }
                writer.append(type.getSymbol())
                        .append("[")
                        .append(String.valueOf(elementNum))
                        .append("] = ");
                boolean first = true;
                for (EquationTermArray<V, E> termArray : termArrays) {
                    if (termArray.write(writer, writeInactiveEquations, elementNum, first)) {
                        first = false;
                    }
                }
                if (!isElementActive(elementNum)) {
                    writer.write(" ]");
                }
                writer.append(System.lineSeparator());
            }
        }
    }
}
