/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.equations;

import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class IncrementalEquationSystemIndex<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>
        extends AbstractEquationSystemIndex<V, E> implements EquationSystemListener<V, E> {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalEquationSystemIndex.class);

    private final TreeSet<Equation<V, E>> sortedEquationsToSolve = new TreeSet<>();

    // variable reference counting in equation terms
    private final NavigableMap<Variable<V>, MutableInt> sortedVariablesToFindRefCount = new TreeMap<>();

    private boolean equationsIndexValid = false;

    private boolean variablesIndexValid = false;

    public IncrementalEquationSystemIndex(EquationSystem<V, E> equationSystem) {
        Objects.requireNonNull(equationSystem).addListener(this);
    }

    private void update() {
        if (!equationsIndexValid) {
            int columnCount = 0;
            for (Equation<V, E> equation : sortedEquationsToSolve) {
                equation.setColumn(columnCount++);
            }
            equationsIndexValid = true;
            LOGGER.debug("Equations index updated ({} columns)", columnCount);
        }

        if (!variablesIndexValid) {
            int rowCount = 0;
            for (Variable<V> variable : sortedVariablesToFindRefCount.keySet()) {
                variable.setRow(rowCount++);
            }
            variablesIndexValid = true;
            LOGGER.debug("Variables index updated ({} rows)", rowCount);
        }
    }

    private void addTerm(EquationTerm<V, E> term) {
        for (Variable<V> variable : term.getVariables()) {
            MutableInt variableRefCount = sortedVariablesToFindRefCount.get(variable);
            if (variableRefCount == null) {
                variableRefCount = new MutableInt(1);
                sortedVariablesToFindRefCount.put(variable, variableRefCount);
                variablesIndexValid = false;
                notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.ADDED);
            } else {
                variableRefCount.increment();
            }
        }
    }

    private void addEquation(Equation<V, E> equation) {
        sortedEquationsToSolve.add(equation);
        equationsIndexValid = false;
        for (EquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                addTerm(term);
            }
        }
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.ADDED);
    }

    private void removeTerm(EquationTerm<V, E> term) {
        for (Variable<V> variable : term.getVariables()) {
            MutableInt variableRefCount = sortedVariablesToFindRefCount.get(variable);
            if (variableRefCount != null) {
                variableRefCount.decrement();
                if (variableRefCount.intValue() == 0) {
                    variable.setRow(-1);
                    sortedVariablesToFindRefCount.remove(variable);
                    variablesIndexValid = false;
                    notifyVariableChange(variable, EquationSystemIndexListener.ChangeType.REMOVED);
                }
            }
        }
    }

    private void removeEquation(Equation<V, E> equation) {
        equation.setColumn(-1);
        sortedEquationsToSolve.remove(equation);
        equationsIndexValid = false;
        for (EquationTerm<V, E> term : equation.getTerms()) {
            if (term.isActive()) {
                removeTerm(term);
            }
        }
        notifyEquationChange(equation, EquationSystemIndexListener.ChangeType.REMOVED);
    }

    @Override
    public void onEquationChange(Equation<V, E> equation, EquationEventType eventType) {
        switch (eventType) {
            case EQUATION_REMOVED:
                if (equation.isActive()) {
                    removeEquation(equation);
                }
                break;

            case EQUATION_DEACTIVATED:
                removeEquation(equation);
                break;

            case EQUATION_CREATED:
                if (equation.isActive()) {
                    addEquation(equation);
                }
                break;

            case EQUATION_ACTIVATED:
                addEquation(equation);
                break;

            default:
                throw new IllegalStateException("Event type not supported: " + eventType);
        }
    }

    @Override
    public void onEquationTermChange(EquationTerm<V, E> term, EquationTermEventType eventType) {
        if (term.getEquation().isActive()) {
            switch (eventType) {
                case EQUATION_TERM_ADDED:
                    if (term.isActive()) {
                        addTerm(term);
                    }
                    break;

                case EQUATION_TERM_ACTIVATED:
                    addTerm(term);
                    break;

                case EQUATION_TERM_DEACTIVATED:
                    removeTerm(term);
                    break;

                default:
                    throw new IllegalStateException("Event type not supported: " + eventType);
            }
        }
    }

    public NavigableSet<Equation<V, E>> getSortedEquationsToSolve() {
        update();
        return sortedEquationsToSolve;
    }

    public NavigableSet<Variable<V>> getSortedVariablesToFind() {
        update();
        return sortedVariablesToFindRefCount.navigableKeySet();
    }
}
