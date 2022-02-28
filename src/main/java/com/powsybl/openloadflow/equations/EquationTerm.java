/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.util.Evaluable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * An equation term, i.e part of the equation sum.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public interface EquationTerm<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends Evaluable {

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
        public void setSelf(EquationTerm<V, E> self) {
            term.setSelf(self);
        }

        @Override
        public ElementType getElementType() {
            return term.getElementType();
        }

        @Override
        public int getElementNum() {
            return term.getElementNum();
        }

        @Override
        public List<Variable<V>> getVariables() {
            return term.getVariables();
        }

        @Override
        public void setStateVector(StateVector stateVector) {
            term.setStateVector(stateVector);
        }

        @Override
        public double eval() {
            return scalarSupplier.getAsDouble() * term.eval();
        }

        @Override
        public Evaluable der(Variable<V> variable) {
            return () -> scalarSupplier.getAsDouble() * term.der(variable).eval();
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

    class VariableEquationTerm<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractEquationTerm<V, E> {

        private final List<Variable<V>> variables;

        VariableEquationTerm(Variable<V> variable) {
            this.variables = List.of(Objects.requireNonNull(variable));
        }

        private Variable<V> getVariable() {
            return variables.get(0);
        }

        @Override
        public ElementType getElementType() {
            return getVariable().getType().getElementType();
        }

        @Override
        public int getElementNum() {
            return getVariable().getElementNum();
        }

        @Override
        public List<Variable<V>> getVariables() {
            return variables;
        }

        @Override
        public double eval() {
            return stateVector.get(getVariable().getRow());
        }

        @Override
        public Evaluable der(Variable<V> variable) {
            return () -> 1;
        }

        @Override
        public double calculateSensi(DenseMatrix x, int column) {
            return x.get(getVariable().getRow(), column);
        }

        @Override
        public void write(Writer writer) throws IOException {
            getVariable().write(writer);
        }
    }

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
     * @param stateVector the state vector
     */
    void setStateVector(StateVector stateVector);

    /**
     * Evaluate equation term.
     * @return value of the equation term
     */
    double eval();

    /**
     * Get partial derivative.
     *
     * @param variable the variable the partial derivative is with respect to
     * @return value of the partial derivative
     */
    Evaluable der(Variable<V> variable);

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
}
