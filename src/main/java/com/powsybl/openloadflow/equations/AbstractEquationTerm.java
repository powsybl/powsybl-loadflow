/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import com.powsybl.math.matrix.DenseMatrix;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractEquationTerm<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements EquationTerm<V, E> {

    private Equation<V, E> equation;

    private boolean active = true;

    protected StateVector sv;

    protected EquationTerm<V, E> self = this;

    @Override
    public void setSv(StateVector sv) {
        this.sv = Objects.requireNonNull(sv);
    }

    @Override
    public Equation<V, E> getEquation() {
        return equation;
    }

    @Override
    public void setEquation(Equation<V, E> equation) {
        this.equation = Objects.requireNonNull(equation);
    }

    @Override
    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            equation.getEquationSystem().notifyEquationTermChange(self, active ? EquationTermEventType.EQUATION_TERM_ACTIVATED
                                                                               : EquationTermEventType.EQUATION_TERM_DEACTIVATED);
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setSelf(EquationTerm<V, E> self) {
        this.self = Objects.requireNonNull(self);
    }

    @Override
    public double calculateSensi(DenseMatrix x, int column) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean hasRhs() {
        return false;
    }

    @Override
    public double rhs() {
        return 0;
    }
}
