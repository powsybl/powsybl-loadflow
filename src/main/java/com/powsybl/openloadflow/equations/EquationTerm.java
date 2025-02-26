/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.util.Derivable;
import com.powsybl.openloadflow.util.Evaluable;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * An equation term, i.e part of the equation sum.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface EquationTerm<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends Derivable<V> {

    static void setActive(Evaluable evaluable, boolean active) {
        if (evaluable instanceof EquationTerm<?, ?> term) {
            term.setActive(active);
        }
    }

    class MultiplyByScalarEquationTerm<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements EquationTerm<V, E> {

        private final EquationTerm<V, E> term;

        private final DoubleSupplier scalarSupplier;

        MultiplyByScalarEquationTerm(EquationTerm<V, E> term, double scalar) {
            this(term, () -> scalar);
        }

        MultiplyByScalarEquationTerm(EquationTerm<V, E> term, DoubleSupplier scalarSupplier) {
            this.term = Objects.requireNonNull(term);
            this.scalarSupplier = Objects.requireNonNull(scalarSupplier);
            term.setSelf(this);
        }

        public double getScalar() {
            return this.scalarSupplier.getAsDouble();
        }

        @Override
        public List<EquationTerm<V, E>> getChildren() {
            return Collections.singletonList(term);
        }

        @Override
        public Equation<V, E> getEquation() {
            return term.getEquation();
        }

        @Override
        public void setEquation(Equation<V, E> equation) {
            term.setEquation(equation);
        }

        @Override
        public void setActive(boolean active) {
            term.setActive(active);
        }

        @Override
        public boolean isActive() {
            return term.isActive();
        }

        @Override
        public void setVectorIndex(int n) {
            term.setVectorIndex(n);
        }

        @Override
        public int getVectorIndex() {
            return term.getVectorIndex();
        }

        @Override
        public void setSelf(EquationTerm<V, E> self) {
            term.setSelf(self);
        }

        @Override
        public ElementType getElementType() {
            return null;
        }

        @Override
        public int getElementNum() {
            return -1;
        }

        @Override
        public List<Variable<V>> getVariables() {
            return term.getVariables();
        }

        @Override
        public void setStateVector(StateVector sv) {
            term.setStateVector(sv);
        }

        @Override
        public double eval() {
            return scalarSupplier.getAsDouble() * term.eval();
        }

        @Override
        public double der(Variable<V> variable) {
            return scalarSupplier.getAsDouble() * term.der(variable);
        }

        @Override
        public boolean hasRhs() {
            return term.hasRhs();
        }

        @Override
        public double rhs() {
            return scalarSupplier.getAsDouble() * term.rhs();
        }

        @Override
        public double calculateSensi(DenseMatrix x, int column) {
            return scalarSupplier.getAsDouble() * term.calculateSensi(x, column);
        }

        @Override
        public void write(Writer writer) throws IOException {
            writer.write(Double.toString(scalarSupplier.getAsDouble()));
            writer.write(" * ");
            term.write(writer);
        }

    }

    static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> EquationTerm<V, E> multiply(EquationTerm<V, E> term, DoubleSupplier scalarSupplier) {
        return new MultiplyByScalarEquationTerm<>(term, scalarSupplier);
    }

    static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> EquationTerm<V, E> multiply(EquationTerm<V, E> term, double scalar) {
        return new MultiplyByScalarEquationTerm<>(term, scalar);
    }

    List<EquationTerm<V, E>> getChildren();

    Equation<V, E> getEquation();

    void setEquation(Equation<V, E> equation);

    boolean isActive();

    void setActive(boolean active);

    void setSelf(EquationTerm<V, E> self);

    ElementType getElementType();

    int getElementNum();

    /**
     * Get the list of variable this equation term depends on.
     * @return the list of variable this equation term depends on.
     */
    List<Variable<V>> getVariables();

    /**
     * Set state vector to use for term evaluation.
     * @param sv the state vector
     */
    void setStateVector(StateVector sv);

    /**
     * Evaluate equation term.
     * @return value of the equation term
     */
    double eval();

    /**
     * Evaluate equation lhs of the equation term
     * @return value of the equation term
     */
    default double evalLhs() {
        return eval() - (hasRhs() ? rhs() : 0);
    }

    /**
     * Get partial derivative.
     *
     * @param variable the variable the partial derivative is with respect to
     * @return value of the partial derivative
     */
    double der(Variable<V> variable);

    /**
     * Check {@link #rhs()} can return a value different from zero.
     *
     * @return true if {@link #rhs()} can return a value different from zero, false otherwise
     */
    boolean hasRhs();

    /**
     * Get part of the partial derivative that has to be moved to right hand side.
     * @return value of part of the partial derivative that has to be moved to right hand side
     */
    double rhs();

    double calculateSensi(DenseMatrix x, int column);

    void write(Writer writer) throws IOException;

    default EquationTerm<V, E> multiply(DoubleSupplier scalarSupplier) {
        return multiply(this, scalarSupplier);
    }

    default EquationTerm<V, E> multiply(double scalar) {
        return multiply(this, scalar);
    }

    default EquationTerm<V, E> minus() {
        return multiply(-1);
    }

    default VectorEngine.VecToVal getVecToVal(Variable<V> v) {
        return null;
    }

    default int getVectorIndex(Variable<V> v) {
        return -1;
    }

    void setVectorIndex(int n);

    int getVectorIndex();
}
